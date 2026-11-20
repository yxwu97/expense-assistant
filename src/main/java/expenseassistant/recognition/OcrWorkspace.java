package expenseassistant.recognition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public final class OcrWorkspace {
    private static final String PREFIX = "page-";
    private static final String MARKER = ".expense-assistant-ocr";
    private static final String MARKER_CONTENT = "expense-assistant/ocr/1\n";
    private final Path directory;
    private final Set<Path> live = new HashSet<>();

    public OcrWorkspace(Path appCache) {
        this.directory = appCache.resolve("ocr").toAbsolutePath().normalize();
    }

    synchronized Path create() throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("OCR 缓存目录不可用");
        }
        Path attempt = Files.createTempDirectory(directory, PREFIX);
        try {
            Files.writeString(attempt.resolve(MARKER), MARKER_CONTENT);
            live.add(attempt);
            return attempt;
        } catch (IOException failure) {
            Files.deleteIfExists(attempt);
            throw failure;
        }
    }

    synchronized void release(Path attempt) throws IOException {
        live.remove(attempt);
        deleteOwned(attempt);
    }

    /** Called while the application holds the instance lock. */
    public synchronized int cleanupOrphans() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (var entries = Files.list(directory)) {
            for (Path attempt : entries.toList()) {
                if (!live.contains(attempt) && deleteOwned(attempt)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private boolean deleteOwned(Path attempt) throws IOException {
        if (!directory.equals(attempt.getParent()) || !attempt.getFileName().toString().startsWith(PREFIX)
                || !Files.isDirectory(attempt, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path marker = attempt.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != MARKER_CONTENT.length()
                || !MARKER_CONTENT.equals(Files.readString(marker))) {
            return false;
        }
        try (var paths = Files.walk(attempt)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!marker.equals(path) && !attempt.equals(path)) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.delete(marker);
        Files.delete(attempt);
        return true;
    }
}
