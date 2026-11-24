package expenseassistant.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReimburserSettingsTest {
    @TempDir Path directory;

    @Test
    void persistsOneUtf8ValueAndIsolatesDifferentDataDirectories() throws Exception {
        var data = directory.resolve("应用数据");
        var settings = new ReimburserSettings(data);
        var other = new ReimburserSettings(directory.resolve("其他数据"));
        assertNull(settings.load());
        assertNull(other.load());

        String name = "𠮷 刘·Anne-Marie O’Connor";
        settings.save(" 　" + name + "\u00a0 ");
        assertEquals(name, Files.readString(data.resolve("reimburser.txt"), StandardCharsets.UTF_8));
        assertEquals(name, new ReimburserSettings(data).load());
        assertNull(other.load());
        other.save("测试乙");
        settings.save("　 ");
        assertNull(new ReimburserSettings(data).load());
        assertEquals("测试乙", other.load());
    }

    @Test
    void rejectsLineBreaksControlsAndMoreThanFortyCodePoints() {
        var settings = new ReimburserSettings(directory);
        String limit = "𠮷".repeat(40);
        settings.save(limit);
        for (String invalid : List.of("测试\n甲", "测试\r甲", "测试\u2028甲", "测试\u2029甲", "测试\t甲",
                "\u0000测试甲", "测试\u0085甲", "测试\uD800甲", limit + "乙")) {
            assertThrows(IllegalArgumentException.class, () -> settings.save(invalid));
            assertEquals(limit, settings.load());
        }
    }

    @Test
    void distinguishesDamagedContentFromAnUnreadablePath() throws Exception {
        var settings = new ReimburserSettings(directory);
        Path file = directory.resolve("reimburser.txt");
        Files.writeString(file, "不应出现在报错里的姓名\n多行");
        var invalid = assertThrows(ReimburserSettings.SettingsException.class, settings::load);
        assertEquals(ReimburserSettings.INVALID_CONTENT, invalid.code());
        assertFalse(invalid.getMessage().contains("不应出现在报错里的姓名"));
        Files.write(file, new byte[]{(byte) 0xC3, 0x28});
        assertEquals(ReimburserSettings.INVALID_CONTENT,
                assertThrows(ReimburserSettings.SettingsException.class, settings::load).code());
        settings.save("重新填写");
        assertEquals("重新填写", settings.load());

        Files.delete(file);
        Files.createDirectory(file);
        var unreadable = assertThrows(ReimburserSettings.SettingsException.class, settings::load);
        assertEquals("REIMBURSER_SETTINGS_UNREADABLE", unreadable.code());
        assertTrue(unreadable.getMessage().contains(file.toString()));
    }

    @Test
    void keepsPreviousValueAndRemovesTemporaryFileOnReplaceFailure() throws Exception {
        var original = new ReimburserSettings(directory);
        original.save("原报销人");
        var failing = new ReimburserSettings(directory, (source, target) -> {
            assertEquals(directory.toAbsolutePath(), source.getParent());
            assertEquals("新报销人", Files.readString(source));
            assertEquals("原报销人", Files.readString(target));
            throw new IOException("受控替换失败");
        });

        var failure = assertThrows(ReimburserSettings.SettingsException.class, () -> failing.save("新报销人"));
        assertEquals("REIMBURSER_SETTINGS_UNWRITABLE", failure.code());
        assertFalse(failure.getMessage().contains("新报销人"));
        assertEquals("原报销人", original.load());
        try (var entries = Files.list(directory)) {
            assertEquals(List.of("reimburser.txt"), entries.map(path -> path.getFileName().toString()).toList());
        }
    }
}
