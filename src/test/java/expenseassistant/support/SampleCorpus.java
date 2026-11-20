package expenseassistant.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import expenseassistant.output.TemplateArchive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SampleCorpus {
    private static final Set<String> SUFFIXES = Set.of(".pdf", ".png", ".jpg", ".jpeg");

    private SampleCorpus() {}

    public record Entry(String id, String caseFolder, String relativePathSha256, String relativePath,
                        String sha256, int pages, String kind, Map<String, String> expected, String basis) {
        private Entry resolve(String path) {
            return new Entry(id, caseFolder, relativePathSha256, path, sha256, pages, kind, expected, basis);
        }
    }

    public static Path defaultSource() {
        return Path.of(System.getProperty("expenseassistant.sampleRoot", "data/2026.10"))
                .toAbsolutePath().normalize();
    }

    public static List<Entry> manifest() throws IOException {
        try (InputStream source = SampleCorpus.class.getResourceAsStream("/corpus-manifest.json")) {
            if (source == null) {
                throw new IOException("样本摘要清册缺失");
            }
            return List.copyOf(new ObjectMapper().readValue(source, new TypeReference<List<Entry>>() {}));
        }
    }

    public static List<Entry> verify(Path originalBatch) throws IOException {
        List<Entry> manifest = manifest();
        Set<String> caseFolders = manifest.stream().map(Entry::caseFolder).collect(Collectors.toSet());
        Map<String, Path> actual = new LinkedHashMap<>();
        if (!Files.isDirectory(originalBatch, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("无法读取原始样本目录 " + originalBatch);
        }
        Files.walkFileTree(originalBatch, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (directory.equals(originalBatch)) {
                    return FileVisitResult.CONTINUE;
                }
                boolean outsideCorpus = directory.getParent().equals(originalBatch)
                        && !caseFolders.contains(Normalizer.normalize(directory.getFileName().toString(), Normalizer.Form.NFC));
                return hidden(directory) || outsideCorpus ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!file.getParent().equals(originalBatch) && attributes.isRegularFile()
                        && !hidden(file) && SUFFIXES.stream().anyMatch(filename::endsWith)) {
                    actual.put(pathDigest(slashPath(originalBatch.relativize(file))), file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        var entries = new ArrayList<Entry>();
        for (Entry expected : manifest) {
            Path file = actual.remove(expected.relativePathSha256());
            if (file == null || !TemplateArchive.sha256(file).equals(expected.sha256())) {
                throw new IOException("样本缺失或摘要不一致 " + expected.id());
            }
            String relativePath = slashPath(originalBatch.relativize(file));
            if (!expected.caseFolder().equals(Normalizer.normalize(relativePath.split("/", 2)[0], Normalizer.Form.NFC))) {
                throw new IOException("样本所属目录不一致 " + expected.id());
            }
            entries.add(expected.resolve(relativePath));
        }
        if (!actual.isEmpty()) {
            throw new IOException("样本目录多出 " + actual.size() + " 份受支持材料");
        }
        return List.copyOf(entries);
    }

    public static List<Entry> verifyAndCopy(Path originalBatch, Path destination) throws IOException {
        List<Entry> entries = verify(originalBatch);
        Path original = originalBatch.toRealPath();
        Path copy = destination.toAbsolutePath().normalize();
        checkOutsideSource(original, copy);
        if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("样本副本目录必须是新目录 " + copy);
        }
        Files.createDirectories(copy.getParent());
        Files.createDirectory(copy);
        for (Entry entry : entries) {
            Path file = copy.resolve(entry.relativePath()).normalize();
            if (!file.startsWith(copy)) {
                throw new IOException("样本清册路径无效 " + entry.id());
            }
            Files.createDirectories(file.getParent());
            Files.copy(original.resolve(entry.relativePath()), file);
            if (!entry.sha256().equals(TemplateArchive.sha256(file))) {
                throw new IOException("样本复制后摘要不一致 " + entry.id());
            }
        }
        return entries;
    }

    public static void checkOutsideSource(Path originalBatch, Path destination) throws IOException {
        Path existing = destination.toAbsolutePath().normalize();
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        Path source = originalBatch.toRealPath();
        for (Path ancestor = existing.toRealPath(); ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSameFile(ancestor, source)) {
                throw new IOException("样本副本或校验输出必须位于原始样本目录之外");
            }
        }
    }

    private static boolean hidden(Path path) throws IOException {
        String filename = path.getFileName().toString();
        return filename.startsWith(".") || filename.startsWith("~$") || Files.isHidden(path);
    }

    private static String slashPath(Path path) {
        return String.join("/", java.util.stream.StreamSupport.stream(path.spliterator(), false)
                .map(Path::toString).toList());
    }

    private static String pathDigest(String relativePath) {
        try {
            var nfc = Normalizer.normalize(relativePath, Normalizer.Form.NFC);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(nfc.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
