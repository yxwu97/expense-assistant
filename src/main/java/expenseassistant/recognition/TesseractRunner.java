package expenseassistant.recognition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import expenseassistant.recognition.PageResult.Bounds;

public class TesseractRunner {
    private static final Logger LOG = Logger.getLogger(TesseractRunner.class.getName());
    private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(30);
    private final Path executable;
    private final Path languageDirectory;
    private final Duration timeout;
    private final OcrWorkspace workspace;
    private final ProcessLauncher launcher;
    private final Semaphore worker = new Semaphore(1, true);
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    public TesseractRunner(Path executable, Path languageDirectory, OcrWorkspace workspace) {
        this(executable, languageDirectory, workspace, PAGE_TIMEOUT, ProcessBuilder::start);
    }

    TesseractRunner(Path executable, Path languageDirectory, OcrWorkspace workspace, Duration timeout,
                    ProcessLauncher launcher) {
        this.executable = executable.toAbsolutePath().normalize();
        this.languageDirectory = languageDirectory.toAbsolutePath().normalize();
        this.workspace = workspace;
        this.timeout = timeout;
        this.launcher = launcher;
    }

    boolean available() {
        return Files.isRegularFile(executable) && Files.isExecutable(executable)
                && Files.isRegularFile(languageDirectory.resolve("chi_sim.traineddata"))
                && Files.isRegularFile(languageDirectory.resolve("eng.traineddata"));
    }

    OcrResult recognize(BufferedImage image, String jobId, int page, int psm, BooleanSupplier cancelled) {
        return recognizeImage(image, null, jobId, page, psm, cancelled);
    }

    OcrResult recognizeImage(BufferedImage image, Path verifiedInput, String jobId, int page, int psm,
                             BooleanSupplier cancelled) {
        long start = System.nanoTime();
        Path attempt = null;
        Process process = null;
        boolean acquired = false;
        String result = "OK";
        try {
            checkCancelled(cancelled);
            if (!available()) {
                result = "OCR_UNAVAILABLE";
                return new OcrResult(null, result);
            }
            while (!(acquired = worker.tryAcquire(100, TimeUnit.MILLISECONDS))) {
                checkCancelled(cancelled);
            }
            checkCancelled(cancelled);
            attempt = workspace.create();
            var input = verifiedInput == null ? attempt.resolve("page.png") : verifiedInput;
            var output = attempt.resolve("text.tsv");
            if (verifiedInput == null && !ImageIO.write(image, "png", input.toFile())) {
                result = "OCR_UNAVAILABLE";
                return new OcrResult(null, result);
            }
            var command = new ProcessBuilder(executable.toString(), input.toString(), "stdout",
                    "--tessdata-dir", languageDirectory.toString(), "-l", "chi_sim+eng",
                    "--oem", "1", "--psm", Integer.toString(psm), "-c", "tessedit_create_tsv=1")
                    .directory(attempt.toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            process = launcher.start(command);
            running.put(jobId, process);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                checkCancelled(cancelled);
                if (System.nanoTime() >= deadline) {
                    result = "OCR_TIMEOUT";
                    return new OcrResult(null, result);
                }
            }
            checkCancelled(cancelled);
            if (process.exitValue() != 0) {
                result = "OCR_FAILED";
                return new OcrResult(null, result);
            }
            return new OcrResult(parseTsv(output, image.getWidth(), image.getHeight()), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = "CANCELLED";
            throw new CancellationException("已取消识别");
        } catch (CancellationException cancellation) {
            result = "CANCELLED";
            throw cancellation;
        } catch (IOException failure) {
            result = "OCR_UNAVAILABLE";
            return new OcrResult(null, result);
        } finally {
            if (process != null) {
                terminate(process);
                running.remove(jobId, process);
            }
            if (attempt != null) {
                try {
                    workspace.release(attempt);
                } catch (IOException cleanupFailed) {
                    log(jobId, page, start, "OCR_CLEANUP_PENDING");
                }
            }
            if (acquired) {
                worker.release();
            }
            log(jobId, page, start, result);
        }
    }

    public void cancel(String jobId) {
        Process process = running.get(jobId);
        if (process != null) {
            killProcessTree(process);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("已取消识别");
        }
    }

    private static void terminate(Process process) {
        if (process.isAlive()) {
            killProcessTree(process);
        }
        boolean interrupted = Thread.interrupted();
        try {
            while (process.isAlive()) {
                try {
                    process.waitFor();
                } catch (InterruptedException again) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void killProcessTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException inaccessibleProcessList) {
            // A sandbox can deny process enumeration while allowing us to terminate the child we created.
        } finally {
            process.destroyForcibly();
        }
    }

    private static void log(String jobId, int page, long started, String result) {
        var safeJob = jobId.matches("[0-9A-Za-z_-]{1,64}") ? jobId : "invalid";
        LOG.fine(() -> "job=" + safeJob + " stage=ocr page=" + page
                + " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " code=" + result);
    }

    private static PageText parseTsv(Path path, int width, int height) throws IOException {
        Map<String, List<Word>> lines = new HashMap<>();
        try (var entries = Files.lines(path)) {
            for (String entry : (Iterable<String>) entries::iterator) {
                String[] cells = entry.split("\t", 12);
                if (cells.length != 12 || !"5".equals(cells[0]) || cells[11].isBlank()) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(cells[6]);
                    int y = Integer.parseInt(cells[7]);
                    int w = Integer.parseInt(cells[8]);
                    int h = Integer.parseInt(cells[9]);
                    var bounds = Bounds.fromEdges(x, y, x + w, y + h, width, height);
                    var key = cells[2] + "/" + cells[3] + "/" + cells[4];
                    lines.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Word(cells[11].strip(), bounds));
                } catch (NumberFormatException invalidRow) {
                    // Ignore malformed geometry and retain other words.
                }
            }
        }
        var rows = lines.values().stream()
                .map(words -> words.stream().sorted(Comparator.comparingDouble(w -> w.bounds().x())).toList())
                .sorted(Comparator.<List<Word>>comparingDouble(row -> row.stream()
                        .mapToDouble(word -> word.bounds().y()).min().orElseThrow())
                        .thenComparingDouble(row -> row.getFirst().bounds().x()))
                .toList();
        var builder = new PageText.Builder();
        for (var row : rows) {
            Word previous = null;
            for (Word word : row) {
                if (previous != null && !adjacentHan(previous, word)) {
                    builder.append(" ", null);
                }
                builder.append(word.text(), word.bounds());
                previous = word;
            }
            builder.append("\n", null);
        }
        return builder.toPageText(PageResult.Method.OCR);
    }

    private static boolean adjacentHan(Word left, Word right) {
        return isHan(left.text().codePointBefore(left.text().length())) && isHan(right.text().codePointAt(0))
                && right.bounds().x() - left.bounds().x() - left.bounds().width() < 0.022;
    }

    private static boolean isHan(int charPoint) {
        return Character.UnicodeScript.of(charPoint) == Character.UnicodeScript.HAN;
    }

    record OcrResult(PageText text, String error) {}
    @FunctionalInterface interface ProcessLauncher { Process start(ProcessBuilder command) throws IOException; }
    private record Word(String text, Bounds bounds) {}
}
