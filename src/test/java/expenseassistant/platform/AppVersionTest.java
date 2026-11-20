package expenseassistant.platform;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppVersionTest {
    @Test
    void filteredResourceMatchesThePomAndDisplay() throws Exception {
        var builder = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder();
        var project = builder.parse(Path.of("pom.xml").toFile()).getDocumentElement();
        String version = project.getElementsByTagName("version").item(0).getTextContent();

        assertEquals(version, AppVersion.value().orElseThrow());
        assertEquals("版本 " + version, AppVersion.displayText());
    }

    @Test
    void missingBlankUnfilteredOrMalformedResourceDisplaysUnknown() {
        assertEquals("版本未知", AppVersion.displayText(AppVersion.readResource(null)));
        for (String content : new String[]{"", "version= ", "version=${project.version}", "version=\\uXYZ0"}) {
            var stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            assertEquals("版本未知", AppVersion.displayText(AppVersion.readResource(stream)), content);
        }
    }
}
