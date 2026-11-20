package expenseassistant.output;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TemplateArchive {
    public static final String BUNDLED_SHA256 = "369f25b49f1b2bae6598b1a0713c6e0a066e43b038ed1464fea7d0f8c25dcd83";
    public static final String BUNDLED_NAME = "3、报销制度-空白表格模板 .xlsx";
    private static final String BUNDLED_RESOURCE = "/output/templates/" + BUNDLED_NAME;
    private final Path directory;

    public TemplateArchive(Path dataDirectory) {
        directory = dataDirectory.resolve("templates");
    }

    public Path installBundled() {
        Path destination = destination(BUNDLED_SHA256);
        if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return locate(BUNDLED_SHA256);
        }
        try (InputStream resource = getClass().getResourceAsStream(BUNDLED_RESOURCE)) {
            if (resource == null) {
                throw new TemplateMismatch("安装包找不到公司模板", destination);
            }
            return install(resource, destination, BUNDLED_SHA256, false);
        } catch (IOException error) {
            throw new TemplateMismatch("无法存档公司模板", destination, error);
        }
    }

    public Path locate(String hash) {
        Path destination = destination(hash);
        try {
            verify(destination, hash);
            return destination;
        } catch (IOException error) {
            throw new TemplateMismatch("无法读取模板存档", destination, error);
        }
    }

    public Path locate(String hash, Path selectedOriginal) {
        Path destination = destination(hash);
        try {
            verify(selectedOriginal, hash);
            if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && hash.equals(sha256(destination))) {
                return destination;
            }
            try (InputStream source = Files.newInputStream(selectedOriginal, LinkOption.NOFOLLOW_LINKS)) {
                return install(source, destination, hash, true);
            }
        } catch (IOException error) {
            throw new TemplateMismatch("无法重定位模板原件", selectedOriginal, error);
        }
    }

    public static String sha256(Path file) throws IOException {
        var digest = sha256Digest();
        try (InputStream source = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = source.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Path install(InputStream source, Path destination, String hash, boolean repair) throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new TemplateMismatch("模板存档目录不可用", directory);
        }
        Path staged = Files.createTempFile(directory, "template-", ".tmp");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            verify(staged, hash);
            try {
                if (repair) {
                    Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else if (Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (FileAlreadyExistsException concurrentlyInstalled) {
                verify(destination, hash);
            }
            verify(destination, hash);
            return destination;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void verify(Path path, String hash) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !hash.equals(sha256(path))) {
            throw new TemplateMismatch("找不到匹配摘要的模板，请重新选择原件", path);
        }
    }

    private Path destination(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) {
            throw new TemplateMismatch("模板摘要格式无效", directory);
        }
        return directory.resolve(hash + ".xlsx");
    }

    public static final class TemplateMismatch extends RuntimeException {
        private final Path path;

        private TemplateMismatch(String message, Path path) {
            this(message, path, null);
        }

        private TemplateMismatch(String message, Path path, Throwable cause) {
            super(message + "（" + path + "）", cause);
            this.path = path;
        }

        public String code() {
            return "TEMPLATE_MISMATCH";
        }

        public Path path() {
            return path;
        }
    }
}
