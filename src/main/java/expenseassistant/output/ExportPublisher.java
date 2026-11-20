package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.intake.PathPolicy;
import expenseassistant.output.ExportService.FileKind;
import expenseassistant.output.ExportService.PublishedFile;
import expenseassistant.output.ExportService.Record;
import expenseassistant.output.ExportService.State;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ExportPublisher {
    static final String STAGE_PREFIX = ".expense-stage-";
    private static final String OWNER = ".expense-assistant-task";
    private final PathPolicy paths;
    private final Path cacheDirectory;
    private final ExcelWriter excel;
    private final WordWriter word = new WordWriter();
    private final Move move;

    @FunctionalInterface
    interface Move {
        void apply(Path from, Path to) throws IOException;
    }

    record Outcome(State state, String code, String message) {}

    ExportPublisher(PathPolicy paths, Path cacheDirectory, TemplateCatalog templates, Move move) {
        this.paths = paths;
        this.cacheDirectory = cacheDirectory.resolve("exports").toAbsolutePath().normalize();
        this.excel = new ExcelWriter(templates);
        this.move = move;
    }

    static void atomicMove(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    List<PublishedFile> stage(Record record, Path staging, ExportSnapshot snapshot, String safeName,
                              Runnable afterDirectories) throws IOException {
        if (!paths.registeredOutputAvailable(record.outputRoot())) {
            throw new CaseException("OUTPUT_NOT_WRITABLE", "输出卷不可访问 " + record.outputRoot());
        }
        createCaseDirectory(record);
        createOwned(staging, record.exportId());
        Path cache = cacheDirectory.resolve(record.exportId());
        Files.createDirectories(cacheDirectory);
        requirePlainDirectory(cacheDirectory);
        createOwned(cache, record.exportId());
        try {
            afterDirectories.run();
            Map<String, Path> sources = copyFrozenSources(snapshot, cache);
            var xlsx = staging.resolve(safeName + "-报销清单.xlsx");
            var docx = staging.resolve(safeName + "-打印附件.docx");
            excel.write(snapshot, xlsx);
            word.write(snapshot, sources, docx);
            excel.evaluateAndVerify(snapshot, xlsx);
            word.verify(snapshot, docx);
            return List.of(manifestEntry(record, FileKind.XLSX, xlsx), manifestEntry(record, FileKind.DOCX, docx));
        } finally {
            discardOwned(cache, record.exportId(), Set.of(), true);
        }
    }

    Outcome publish(Record record, Path staging, Runnable afterMove) {
        if (!paths.registeredOutputAvailable(record.outputRoot())) {
            return notAvailable(record.outputRoot());
        }
        Path target = record.finalDirectory();
        try {
            var parent = attributes(target.getParent());
            if (parent == null) {
                return paths.registeredOutputAvailable(record.outputRoot())
                        ? failure("STAGING_INVALID", "成品与暂存目录均已缺失") : notAvailable(record.outputRoot());
            }
            if (!plainDirectory(target.getParent(), parent)) {
                return conflict(target.getParent());
            }
            var destination = attributes(target);
            if (destination != null) {
                return classifyTarget(record, target);
            }
            if (!matches(staging, record, true)) {
                return paths.registeredOutputAvailable(record.outputRoot())
                        ? failure("STAGING_INVALID", "待发布文件缺失或摘要不合 " + staging) : notAvailable(record.outputRoot());
            }
            // The owner marker is only needed until the pair has a durable manifest.
            Files.deleteIfExists(staging.resolve(OWNER));
            if (attributes(target) != null) {
                return classifyTarget(record, target);
            }
            try {
                move.apply(staging, target);
            } catch (AtomicMoveNotSupportedException unsupported) {
                return failure("OUTPUT_NOT_ATOMIC", "输出卷不支持整目录原子改名 " + record.outputRoot());
            } catch (IOException moveFailed) {
                if (attributes(target) != null) {
                    return classifyTarget(record, target);
                }
                if (!paths.registeredOutputAvailable(record.outputRoot())) {
                    return notAvailable(record.outputRoot());
                }
                return failure("OUTPUT_NOT_WRITABLE", "无法发布成品目录 " + target);
            }
            afterMove.run();
            return classifyTarget(record, target);
        } catch (IOException inaccessible) {
            return notAvailable(record.outputRoot());
        }
    }

    private Outcome classifyTarget(Record record, Path target) throws IOException {
        if (matches(target, record, false)) {
            return published();
        }
        return paths.registeredOutputAvailable(record.outputRoot()) ? conflict(target) : notAvailable(record.outputRoot());
    }

    Outcome recover(Record record, Path staging) {
        if (record.state() == State.GENERATING) {
            return failure("GENERATION_INTERRUPTED", "生成在两份成品登记前中断");
        }
        return publish(record, staging, () -> {});
    }

    void cleanupFailed(Record record, Path staging, String safeName) {
        discardOwned(cacheDirectory.resolve(record.exportId()), record.exportId(), Set.of(), true);
        if (!paths.registeredOutputAvailable(record.outputRoot())) {
            return;
        }
        try {
            if (!plainDirectory(staging.getParent(), attributes(staging.getParent()))) {
                return;
            }
            var names = Set.of(safeName + "-报销清单.xlsx", safeName + "-打印附件.docx");
            if (attributes(staging.resolve(OWNER)) == null && matches(staging, record, false)) {
                // A crash or atomic-move denial may leave a fully verified markerless stage.
                for (var entry : record.files()) {
                    Files.deleteIfExists(staging.resolve(entry.path().getFileName()));
                }
                Files.deleteIfExists(staging);
            } else {
                discardOwned(staging, record.exportId(), names, false);
            }
        } catch (IOException inaccessible) {
            // An uncertain directory can be reviewed later without touching a previous product.
        }
    }

    static Path verifiedFile(Record record, FileKind kind) {
        PublishedFile file = record.file(kind);
        if (record.state() != State.SUCCEEDED || file == null) {
            throw new CaseException("EXPORT_NOT_READY", "成品尚未发布");
        }
        try {
            if (!plainDirectory(record.finalDirectory(), attributes(record.finalDirectory())) || attributes(file.path()) == null) {
                throw new CaseException("OUTPUT_MISSING", "成品已缺失 " + file.path());
            }
            if (!matches(file.path(), file)) {
                throw new CaseException("OUTPUT_CHANGED", "成品已被修改 " + file.path());
            }
            return file.path();
        } catch (IOException inaccessible) {
            throw new CaseException("OUTPUT_MISSING", "无法读取成品 " + file.path(), inaccessible);
        }
    }

    private static void createCaseDirectory(Record record) throws IOException {
        Path directory = record.finalDirectory().getParent();
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException existing) {
            // Prior successful exports share this per-case directory.
        }
        requirePlainDirectory(directory);
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        if (!plainDirectory(directory, attributes(directory))) {
            throw new IOException("目录不可用 " + directory);
        }
    }

    private static boolean plainDirectory(Path directory, BasicFileAttributes info) throws IOException {
        return info != null && info.isDirectory() && directory.toRealPath()
                .equals(directory.getParent().toRealPath().resolve(directory.getFileName()));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void createOwned(Path directory, String id) throws IOException {
        Files.createDirectory(directory);
        Files.writeString(directory.resolve(OWNER), id, StandardOpenOption.CREATE_NEW, StandardOpenOption.SYNC);
    }

    private static Map<String, Path> copyFrozenSources(ExportSnapshot snapshot, Path cache) throws IOException {
        Path folder = Path.of(snapshot.caseFolder()).toAbsolutePath().normalize();
        Map<String, Path> copies = new HashMap<>();
        for (var page : snapshot.pages()) {
            if (copies.containsKey(page.docId())) {
                continue;
            }
            Path relative = Path.of(page.relativePath());
            Path source = folder.resolve(relative).normalize();
            String suffix = suffix(source);
            Path copy = cache.resolve("source-" + copies.size() + suffix);
            if (relative.isAbsolute() || !source.startsWith(folder) || suffix.isEmpty()) {
                throw sourceChanged(page.relativePath(), null);
            }
            MessageDigest digest = sha256();
            try (var input = new DigestInputStream(openSource(source, page.relativePath()), digest);
                 var output = Files.newOutputStream(copy, StandardOpenOption.CREATE_NEW)) {
                var buffer = new byte[128 * 1024];
                for (int count = readSource(input, buffer, page.relativePath()); count >= 0;
                     count = readSource(input, buffer, page.relativePath())) {
                    output.write(buffer, 0, count);
                }
            }
            if (!page.sha256().equals(HexFormat.of().formatHex(digest.digest()))) {
                throw sourceChanged(page.relativePath(), null);
            }
            copies.put(page.docId(), copy);
        }
        return copies;
    }

    private static java.io.InputStream openSource(Path path, String relativePath) {
        try {
            var info = attributes(path);
            if (info == null || !info.isRegularFile() || !path.toRealPath().equals(path)) {
                throw sourceChanged(relativePath, null);
            }
            return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw sourceChanged(relativePath, failure);
        }
    }

    private static int readSource(DigestInputStream input, byte[] buffer, String name) {
        try {
            return input.read(buffer);
        } catch (IOException failure) {
            throw sourceChanged(name, failure);
        }
    }

    private static String suffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String suffix = dot >= 0 ? name.substring(dot) : "";
        return Set.of(".pdf", ".png", ".jpg", ".jpeg").contains(suffix) ? suffix : "";
    }

    private static CaseException sourceChanged(String relativePath, Throwable cause) {
        return new CaseException("SOURCE_CHANGED", "凭证已更换或不可读 " + relativePath + "，请重新扫描", cause);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PublishedFile manifestEntry(Record record, FileKind kind, Path staged) throws IOException {
        try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        long size = Files.size(staged);
        if (size <= 0) {
            throw new IOException("成品为空 " + staged);
        }
        return new PublishedFile(kind, record.finalDirectory().resolve(staged.getFileName()), TemplateArchive.sha256(staged), size);
    }

    private static boolean matches(Path directory, Record record, boolean allowOwner) throws IOException {
        if (!plainDirectory(directory, attributes(directory)) || record.files().size() != 2) {
            return false;
        }
        var byName = new HashMap<String, PublishedFile>();
        for (PublishedFile manifest : record.files()) {
            byName.put(manifest.path().getFileName().toString(), manifest);
        }
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (allowOwner && name.equals(OWNER) && owned(directory, record.exportId())) {
                    continue;
                }
                PublishedFile expected = byName.remove(name);
                if (expected == null || !matches(path, expected)) {
                    return false;
                }
            }
        }
        return byName.isEmpty();
    }

    private static boolean matches(Path path, PublishedFile manifest) throws IOException {
        var info = attributes(path);
        return info != null && info.isRegularFile() && info.size() == manifest.size()
                && manifest.sha256().equals(TemplateArchive.sha256(path));
    }

    private static boolean owned(Path directory, String id) throws IOException {
        Path mark = directory.resolve(OWNER);
        var info = attributes(mark);
        return info != null && info.isRegularFile() && info.size() == id.length() && id.equals(Files.readString(mark));
    }

    private static void discardOwned(Path directory, String id, Set<String> allowed, boolean cache) {
        try {
            if (!plainDirectory(directory, attributes(directory)) || !owned(directory, id)) {
                return;
            }
            List<Path> parts;
            try (var children = Files.list(directory)) {
                parts = children.toList();
            }
            for (Path part : parts) {
                String name = part.getFileName().toString();
                boolean expected = cache ? name.matches("source-\\d+\\.(pdf|png|jpe?g)") : allowed.contains(name);
                if (!name.equals(OWNER) && (!expected || !Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS))) {
                    return;
                }
            }
            for (Path part : parts) {
                if (!part.getFileName().toString().equals(OWNER)) {
                    Files.deleteIfExists(part);
                }
            }
            Files.delete(directory.resolve(OWNER));
            Files.delete(directory);
        } catch (IOException inaccessible) {
            // An uncertain or inaccessible task directory is left untouched.
        }
    }

    private static Outcome published() {
        return new Outcome(State.SUCCEEDED, null, null);
    }

    private static Outcome conflict(Path path) {
        return new Outcome(State.CONFLICT, "OUTPUT_CONFLICT", "成品路径已存在且内容不同 " + path);
    }

    private static Outcome failure(String code, String message) {
        return new Outcome(State.FAILED, code, message);
    }

    private static Outcome notAvailable(Path root) {
        return new Outcome(State.READY_TO_PUBLISH, "OUTPUT_NOT_WRITABLE", "输出卷暂不可确认，接回后复查 " + root);
    }
}
