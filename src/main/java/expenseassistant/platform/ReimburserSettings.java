package expenseassistant.platform;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public final class ReimburserSettings {
    public static final String INVALID_CONTENT = "REIMBURSER_SETTINGS_INVALID";
    private final Path file;
    private final Replace replace;

    @FunctionalInterface
    interface Replace {
        void move(Path source, Path target) throws IOException;
    }

    public ReimburserSettings(Path dataDirectory) {
        this(dataDirectory, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    ReimburserSettings(Path dataDirectory, Replace replace) {
        this.file = dataDirectory.toAbsolutePath().normalize().resolve("reimburser.txt");
        this.replace = replace;
    }

    public String load() {
        try {
            if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException("设置路径不是普通文件");
            }
            return normalize(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return null;
        } catch (CharacterCodingException | IllegalArgumentException invalid) {
            throw new SettingsException(INVALID_CONTENT, "报销人设置文件内容无效，请在「报销人设置」中重新填写并保存。\n"
                    + file, invalid);
        } catch (IOException | SecurityException unreadable) {
            throw new SettingsException("REIMBURSER_SETTINGS_UNREADABLE", "无法读取报销人设置，请检查文件路径和权限。\n"
                    + file, unreadable);
        }
    }

    public void save(String name) {
        String value = normalize(name);
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".reimburser-", ".tmp");
            Files.writeString(temporary, value == null ? "" : value, StandardCharsets.UTF_8);
            replace.move(temporary, file);
        } catch (IOException | SecurityException failure) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw new SettingsException("REIMBURSER_SETTINGS_UNWRITABLE", "无法保存报销人设置，请检查数据目录权限和磁盘空间。\n"
                    + file, failure);
        }
    }

    public static String normalize(String name) {
        if (name == null) return null;
        if (name.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || codePoint == '\u2028' || codePoint == '\u2029'
                || Character.getType(codePoint) == Character.SURROGATE)) {
            throw new IllegalArgumentException("姓名只能填写一行，且不能含控制字符。");
        }
        int start = 0;
        int end = name.length();
        while (start < end && Character.isSpaceChar(name.charAt(start))) start++;
        while (start < end && Character.isSpaceChar(name.charAt(end - 1))) end--;
        if (start == end) return null;
        if (name.codePointCount(start, end) > 40) {
            throw new IllegalArgumentException("姓名最多填写 40 个字符。");
        }
        return name.substring(start, end);
    }

    public static final class SettingsException extends RuntimeException {
        private final String code;

        private SettingsException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() { return code; }
    }
}
