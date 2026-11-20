package expenseassistant.output;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateArchiveTest {
    @TempDir Path temporary;

    @Test
    void bundlesOriginalBytesAndProtectsArchivedVersions() throws Exception {
        var templates = new TemplateArchive(temporary);
        var bundled = templates.installBundled();
        assertEquals(TemplateArchive.BUNDLED_SHA256, TemplateArchive.sha256(bundled));
        var original = Files.copy(bundled, temporary.resolve("核验过的原件.xlsx"));
        var priorOriginal = Files.writeString(temporary.resolve("测试用旧模板.xlsx"), "older-template-fixture");
        var priorHash = TemplateArchive.sha256(priorOriginal);
        var prior = templates.locate(priorHash, priorOriginal);
        assertEquals(bundled, templates.installBundled());
        assertEquals(prior, templates.locate(priorHash));

        Files.writeString(bundled, "broken");
        assertEquals("TEMPLATE_MISMATCH", assertThrows(TemplateArchive.TemplateMismatch.class,
                templates::installBundled).code());
        assertThrows(TemplateArchive.TemplateMismatch.class,
                () -> templates.locate(TemplateArchive.BUNDLED_SHA256, priorOriginal));
        assertEquals("broken", Files.readString(bundled));
        assertEquals(bundled, templates.locate(TemplateArchive.BUNDLED_SHA256, original));
        assertEquals(TemplateArchive.BUNDLED_SHA256, TemplateArchive.sha256(bundled));
        try (var archives = Files.list(bundled.getParent())) {
            assertFalse(archives.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }
}
