package expenseassistant.recognition;

import java.awt.image.BufferedImage;
import java.security.DigestInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import expenseassistant.platform.AppPaths;
import expenseassistant.recognition.PageResult.FieldCandidate;
import expenseassistant.recognition.PageResult.Kind;

public final class RecognitionService {
    private static final Logger LOG = Logger.getLogger(RecognitionService.class.getName());
    private static final float OCR_DPI = 300;
    private static final String PREPROCESSING = "300dpi-orient-v1";
    private static final int MAX_CACHED_DOCUMENTS = 128;

    private final PageParser parser = new PageParser();
    private final TesseractRunner runner;
    private final OcrWorkspace workspace;
    private final Map<String, AtomicBoolean> active = new ConcurrentHashMap<>();
    private final Map<CacheKey, List<PageResult>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, List<PageResult>> eldest) {
            return size() > MAX_CACHED_DOCUMENTS;
        }
    };

    public RecognitionService(TesseractRunner runner, OcrWorkspace workspace) {
        this.runner = runner;
        this.workspace = workspace;
    }

    public static RecognitionService fromPaths(AppPaths paths) {
        String configured = System.getProperty("expenseassistant.ocrRoot");
        Path root = configured == null || configured.isBlank()
                ? paths.installDirectory().resolve("ocr") : Path.of(configured).toAbsolutePath();
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        var workspace = new OcrWorkspace(paths.cacheDirectory());
        var runner = new TesseractRunner(root.resolve(windows ? "bin/tesseract.exe" : "bin/tesseract"),
                root.resolve("share/tessdata"), workspace);
        return new RecognitionService(runner, workspace);
    }

    public OcrWorkspace workspace() {
        return workspace;
    }

    public List<PageResult> extract(Path input, String contentSha256, String jobId) {
        return extract(input, contentSha256, jobId, PageParser.VERSION, () -> false);
    }

    public List<PageResult> extract(Path input, String contentSha256, String jobId, String parserVersion,
                                    BooleanSupplier cancelled) {
        return extract(input, contentSha256, jobId, parserVersion, cancelled, 0);
    }

    /** A retry rereads the original and bypasses the result cache. */
    public PageResult retryPage(Path input, String contentSha256, String jobId, int page,
                                BooleanSupplier cancelled) {
        if (page < 1) {
            throw new IllegalArgumentException("凭证页码已失效");
        }
        var pages = extract(input, contentSha256, jobId, PageParser.VERSION, cancelled, page);
        return pages.stream().filter(candidate -> candidate.pageNo() == page).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("凭证页码已失效"));
    }

    private List<PageResult> extract(Path input, String contentSha256, String jobId, String parserVersion,
                                     BooleanSupplier cancelled, int onlyPage) {
        var localCancellation = new AtomicBoolean();
        if (active.putIfAbsent(jobId, localCancellation) != null) {
            throw new IllegalStateException("同一识别任务已在执行");
        }
        BooleanSupplier shouldCancel = () -> localCancellation.get() || cancelled.getAsBoolean();
        Path attempt = null;
        try {
            checkCancelled(shouldCancel);
            attempt = workspace.create();
            boolean pdf = PageImages.isPdf(input);
            Path snapshot = attempt.resolve(pdf ? "source.pdf" : "source.image");
            verifyAndCopy(input, snapshot, contentSha256, shouldCancel);
            var key = new CacheKey(contentSha256, parserVersion, PREPROCESSING);
            synchronized (cache) {
                List<PageResult> hit = onlyPage != 0 ? null : cache.get(key);
                if (hit != null) {
                    return hit;
                }
            }
            List<PageResult> result = pdf ? pdf(snapshot, jobId, parserVersion, shouldCancel, onlyPage)
                    : onlyPage > 1 ? List.of() : image(snapshot, jobId, parserVersion, shouldCancel);
            checkCancelled(shouldCancel);
            if (onlyPage == 0 && result.stream().allMatch(page -> parser.reliable(page) && page.warnings().stream()
                    .noneMatch(warning -> warning.startsWith("OCR_")))) {
                synchronized (cache) {
                    cache.put(key, result);
                }
            }
            return result;
        } catch (IOException io) {
            throw new RecognitionFailure("OCR_UNAVAILABLE", io);
        } finally {
            active.remove(jobId, localCancellation);
            if (attempt != null) {
                try {
                    workspace.release(attempt);
                } catch (IOException cleanupFailed) {
                    log(jobId, 0, System.nanoTime(), "OCR_CLEANUP_PENDING");
                }
            }
        }
    }

    public void cancel(String jobId) {
        AtomicBoolean state = active.get(jobId);
        if (state != null) {
            state.set(true);
        }
        runner.cancel(jobId);
    }

    private List<PageResult> pdf(Path snapshot, String jobId, String version, BooleanSupplier cancelled, int onlyPage) {
        try (var document = Loader.loadPDF(snapshot.toFile())) {
            if (document.getNumberOfPages() == 0) {
                return List.of(unrenderable(1, version, "PAGE_UNRENDERABLE"));
            }
            var pages = new ArrayList<PageResult>();
            var renderer = new PDFRenderer(document);
            int startPage = onlyPage > 0 ? onlyPage : 1;
            int endPage = onlyPage > 0 ? Math.min(onlyPage, document.getNumberOfPages()) : document.getNumberOfPages();
            for (int page = startPage; page <= endPage; page++) {
                checkCancelled(cancelled);
                long start = System.nanoTime();
                PageResult text;
                try {
                    text = parser.extractFields(PdfPageText.read(document, page), page, version);
                } catch (IOException | IllegalArgumentException failure) {
                    text = new PageResult(page, Kind.UNKNOWN, true, List.of(), List.of(), List.of(),
                            List.of("PDF_TEXT_UNAVAILABLE"), version);
                }
                PageResult result = text;
                if (!parser.reliable(text)) {
                    try {
                        BufferedImage rendered = renderer.renderImageWithDPI(page - 1, OCR_DPI, ImageType.RGB);
                        result = ocr(rendered, null, text, jobId, version, cancelled);
                    } catch (IOException | IllegalArgumentException failure) {
                        result = new PageResult(page, text.kind(), false, text.fields(), text.segments(),
                                text.summaryReferences(), append(text.warnings(), "PAGE_UNRENDERABLE"), version);
                    }
                }
                pages.add(result);
                log(jobId, page, start, result.warnings().isEmpty() ? "OK" : result.warnings().getFirst());
            }
            return List.copyOf(pages);
        } catch (InvalidPasswordException encrypted) {
            return List.of(unrenderable(onlyPage == 0 ? 1 : onlyPage, version, "PDF_ENCRYPTED"));
        } catch (IOException | IllegalArgumentException unrenderable) {
            return List.of(unrenderable(onlyPage == 0 ? 1 : onlyPage, version, "PAGE_UNRENDERABLE"));
        }
    }

    private List<PageResult> image(Path snapshot, String jobId, String version, BooleanSupplier cancelled) {
        var blank = new PageResult(1, Kind.UNKNOWN, true, List.of(), List.of(), List.of(), List.of(), version);
        try {
            var image = PageImages.decode(snapshot);
            return List.of(ocr(image.pixels(), image.orientation() == 1 ? snapshot : null,
                    blank, jobId, version, cancelled));
        } catch (IOException | IllegalArgumentException failure) {
            return List.of(unrenderable(1, version, "PAGE_UNRENDERABLE"));
        }
    }

    private PageResult ocr(BufferedImage image, Path verifiedInput, PageResult text, String jobId, String version,
                           BooleanSupplier cancelled) {
        var attempt = verifiedInput == null ? runner.recognize(image, jobId, text.pageNo(), 3, cancelled)
                : runner.recognizeImage(image, verifiedInput, jobId, text.pageNo(), 3, cancelled);
        if (attempt.error() != null) {
            var result = new PageResult(text.pageNo(), text.kind(), true, text.fields(), text.segments(),
                    text.summaryReferences(), append(text.warnings(), attempt.error()), version);
            return text.kind() == Kind.MILEAGE ? MileageRouteSelector.needsReview(result) : result;
        }
        var recognized = parser.extractFields(attempt.text(), text.pageNo(), version);
        if (recognized.kind() == Kind.MILEAGE) {
            recognized = MileageRouteSelector.select(image, attempt.text(), recognized);
        } else if (recognized.kind() == Kind.INVOICE && recognized.value("invoiceDate") == null
                && text.value("invoiceDate") == null && recognized.value("invoiceNumber") != null) {
            recognized = retryInvoiceHeader(image, recognized, jobId, version, cancelled);
        }
        PageResult combined = merge(text, recognized, version);
        if (!parser.reliable(combined)) {
            if (combined.kind() == Kind.MILEAGE) {
                return MileageRouteSelector.needsReview(combined);
            }
            return new PageResult(combined.pageNo(), combined.kind(), true, combined.fields(), combined.segments(),
                    combined.summaryReferences(), append(combined.warnings(), "FIELDS_NEED_REVIEW"), version);
        }
        return combined;
    }

    private PageResult retryInvoiceHeader(BufferedImage image, PageResult page, String jobId, String version,
                                          BooleanSupplier cancelled) {
        int x = (int) (image.getWidth() * 0.72);
        int y = (int) (image.getHeight() * 0.035);
        int width = (int) (image.getWidth() * 0.99) - x;
        int height = (int) (image.getHeight() * 0.177) - y;
        if (width <= 0 || height <= 0) {
            return page;
        }
        var cropped = image.getSubimage(x, y, width, height);
        var result = runner.recognize(cropped, jobId, page.pageNo(), 6, cancelled);
        if (result.error() != null) {
            return new PageResult(page.pageNo(), page.kind(), true, page.fields(), page.segments(),
                    page.summaryReferences(), append(page.warnings(), result.error()), version);
        }
        var header = parser.extractFields(result.text(), page.pageNo(), version);
        var fields = new ArrayList<>(page.fields());
        header.field("invoiceDate").ifPresent(candidate -> {
            var box = candidate.bounds();
            var positioned = box == null ? null : PageResult.Bounds.fromEdges(x + box.x() * width,
                    y + box.y() * height, x + (box.x() + box.width()) * width,
                    y + (box.y() + box.height()) * height, image.getWidth(), image.getHeight());
            fields.add(new FieldCandidate(candidate.key(), candidate.raw(), candidate.normalized(), candidate.method(),
                    positioned, candidate.validation()));
        });
        return new PageResult(page.pageNo(), page.kind(), true, fields, page.segments(),
                page.summaryReferences(), page.warnings(), version);
    }

    private static PageResult merge(PageResult text, PageResult ocr, String version) {
        if (ocr.kind() == Kind.UNKNOWN && text.kind() != Kind.UNKNOWN) {
            return text;
        }
        var warnings = new LinkedHashSet<>(text.warnings());
        warnings.addAll(ocr.warnings());
        boolean differentKinds = ocr.kind() != Kind.UNKNOWN && text.kind() != Kind.UNKNOWN && ocr.kind() != text.kind();
        if (differentKinds) {
            warnings.add("DOCUMENT_KIND_CONFLICT");
        }
        var fields = mergeFields(text, ocr, warnings, differentKinds);
        return new PageResult(text.pageNo(), text.kind() == Kind.UNKNOWN ? ocr.kind() : text.kind(), true, fields,
                !differentKinds && text.segments().isEmpty() ? ocr.segments() : text.segments(),
                !differentKinds && text.summaryReferences().isEmpty() ? ocr.summaryReferences() : text.summaryReferences(),
                List.copyOf(warnings), version);
    }

    private static List<PageResult.FieldCandidate> mergeFields(PageResult text, PageResult ocr,
                                                                Set<String> warnings, boolean differentKinds) {
        String textGross = text.value("gross");
        String ocrGross = ocr.value("gross");
        String textCurrency = text.value("currency");
        String ocrCurrency = ocr.value("currency");
        String currency = textCurrency != null ? textCurrency : ocrCurrency;
        boolean amountConflict = warnings.contains("AMOUNT_CONFLICT")
                || differs(textGross, ocrGross) && !sameAmount(textGross, ocrGross);
        boolean currencyConflict = warnings.contains("CURRENCY_CONFLICT") || differs(textCurrency, ocrCurrency)
                || "CNY".equals(currency) && warnings.contains("CURRENCY_NEEDS_REVIEW") && hasBareDollar(text, ocr);
        boolean uncertain = warnings.contains("CURRENCY_NEEDS_REVIEW") && "CNY".equals(currency)
                && !hasExplicitCurrency(text, ocr);
        if (amountConflict) warnings.add("AMOUNT_CONFLICT");
        if (currencyConflict) warnings.add("CURRENCY_CONFLICT");
        if ((currency != null && !uncertain) || currencyConflict) warnings.remove("CURRENCY_NEEDS_REVIEW");

        var fields = new ArrayList<>(text.fields());
        for (var candidate : ocr.fields()) {
            boolean billingField = "gross".equals(candidate.key()) || "currency".equals(candidate.key());
            if (differentKinds && !(billingField && (amountConflict || currencyConflict))) continue;
            if (!fields.contains(candidate)) {
                fields.add(candidate);
            }
            var previous = text.value(candidate.key());
            if (!billingField && !MileageRouteSelector.CANDIDATE.equals(candidate.key())
                    && differs(previous, candidate.normalized())) {
                warnings.add("FIELD_CONFLICT");
            }
        }
        fields.replaceAll(field -> switch (field.key()) {
            case "gross" -> amountConflict || currencyConflict || uncertain ? normalized(field, null)
                    : field.normalized() != null && textGross != null && sameAmount(textGross, field.normalized())
                    ? normalized(field, textGross) : field;
            case "currency" -> currencyConflict || uncertain ? normalized(field, null) : field;
            default -> field;
        });
        return fields;
    }

    private static boolean hasBareDollar(PageResult first, PageResult second) {
        return java.util.stream.Stream.concat(first.fields().stream(), second.fields().stream())
                .anyMatch(field -> "currency".equals(field.key()) && Set.of("$", "＄").contains(field.raw().strip()));
    }

    private static boolean hasExplicitCurrency(PageResult first, PageResult second) {
        return java.util.stream.Stream.concat(first.fields().stream(), second.fields().stream())
                .anyMatch(field -> "currency".equals(field.key()) && field.normalized() != null
                        && field.method() != PageResult.Method.RULE && !"Y".equalsIgnoreCase(field.raw().strip()));
    }

    private static boolean differs(String first, String second) {
        return first != null && second != null && !first.equals(second);
    }

    private static boolean sameAmount(String first, String second) {
        try {
            return new java.math.BigDecimal(first).compareTo(new java.math.BigDecimal(second)) == 0;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private static PageResult.FieldCandidate normalized(PageResult.FieldCandidate field, String value) {
        return new PageResult.FieldCandidate(field.key(), field.raw(), value, field.method(), field.bounds(), field.validation());
    }

    private static PageResult unrenderable(int page, String version, String warning) {
        return new PageResult(page, Kind.UNKNOWN, false, List.of(), List.of(), List.of(),
                List.of(warning), version);
    }

    private static List<String> append(List<String> warnings, String code) {
        ArrayList<String> all = new ArrayList<>(warnings);
        if (!all.contains(code)) {
            all.add(code);
        }
        return all;
    }

    private static void verifyAndCopy(Path input, Path output, String hash, BooleanSupplier cancelled) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var in = new DigestInputStream(Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS), digest);
             OutputStream out = Files.newOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) != -1; ) {
                checkCancelled(cancelled);
                out.write(buffer, 0, read);
            }
        } catch (IOException failure) {
            throw new RecognitionFailure("SOURCE_CHANGED", failure);
        }
        if (!hash.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new RecognitionFailure("SOURCE_CHANGED", null);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("已取消识别");
        }
    }

    private static void log(String job, int page, long started, String code) {
        String safeJob = job.matches("[0-9A-Za-z_-]{1,64}") ? job : "invalid";
        LOG.fine(() -> "job=" + safeJob + " stage=recognition page=" + page + " elapsedMs="
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " code=" + code);
    }

    private record CacheKey(String sha256, String parserVersion, String preprocessing) {}

    public static final class RecognitionFailure extends RuntimeException {
        private final String code;

        RecognitionFailure(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
