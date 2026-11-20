package expenseassistant.platform;

import expenseassistant.output.TemplateArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static expenseassistant.platform.WpsLauncher.Component.DOCX;
import static expenseassistant.platform.WpsLauncher.Component.XLSX;
import static expenseassistant.platform.WpsLauncher.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class WpsLauncherTest {
    @TempDir Path temporary;

    @Test
    void macSelectsVerifiedBundleAndPassesChinesePathAsSingleArgument() throws Exception {
        Path bundle = Files.createDirectories(temporary.resolve("办公应用 WPS.app"));
        Files.createDirectories(bundle.resolve("Contents/MacOS"));
        Files.writeString(bundle.resolve("Contents/Info.plist"), "fixture");
        Path executable = Files.writeString(bundle.resolve("Contents/MacOS/wpsoffice.exe"), "fixture");
        assertTrue(executable.toFile().setExecutable(true) || Files.isExecutable(executable));
        Path file = Files.writeString(temporary.resolve("差旅费 11 月.xlsx"), "checksum fixture");
        var commands = new TestCommands(arguments -> arguments.getLast().equals(bundle.resolve("Contents/Info.plist").toString())
                ? arguments.contains("CFBundleIdentifier") ? "com.kingsoft.wpsoffice.mac\n" : executable.getFileName().toString()
                : "");
        var launcher = new WpsLauncher(WpsLauncher.Platform.MAC, temporary, Map.of(), commands);
        launcher.selectInstallation(bundle);
        assertEquals(OPENED, launcher.openInWps(file, TemplateArchive.sha256(file), XLSX).status());
        assertEquals(List.of("/usr/bin/open", "-a", bundle.toString(), file.toString()), commands.last());
        commands.fail = true;
        assertEquals(LAUNCH_FAILED, launcher.openInWps(file, TemplateArchive.sha256(file), XLSX).status());
    }

    @Test
    void windowsUsesMatchingComponentFromSelectedDirectoryOrRegistry() throws Exception {
        Path office = Files.createDirectories(temporary.resolve("办公程序 WPS"));
        Path et = Files.writeString(office.resolve("et.exe"), "fixture");
        Path wps = Files.writeString(office.resolve("wps.exe"), "fixture");
        Path pdf = Files.writeString(office.resolve("wpspdf.exe"), "fixture");
        Path workbook = Files.writeString(temporary.resolve("Excel 报销清单.xlsx"), "fixture");
        Path document = Files.writeString(temporary.resolve("Word 打印附件.docx"), "fixture");
        Path invoice = Files.writeString(temporary.resolve("纸票 1.pdf"), "fixture");
        var commands = new TestCommands(arguments -> arguments.stream().anyMatch(key -> key.endsWith("\\et.exe"))
                ? "    (Default)    REG_SZ    \"" + et + "\"\r\n" : "");
        var launcher = new WpsLauncher(WpsLauncher.Platform.WINDOWS, temporary, Map.of("SystemRoot", temporary.toString()), commands);
        assertEquals(OPENED, launcher.openInWps(workbook, TemplateArchive.sha256(workbook), XLSX).status());
        assertEquals(List.of(et.toString(), workbook.toString()), commands.last());
        launcher.selectInstallation(et);
        assertEquals(OPENED, launcher.openInWps(document, TemplateArchive.sha256(document), DOCX).status());
        assertEquals(List.of(wps.toString(), document.toString()), commands.last());
        assertEquals(OPENED, launcher.openSource(invoice, TemplateArchive.sha256(invoice)).status());
        assertEquals(List.of(pdf.toString(), invoice.toString()), commands.last());
    }

    @Test
    void unavailableChangedAndMissingWpsHaveDistinctOutcomes() throws Exception {
        Path file = Files.writeString(temporary.resolve("附件.docx"), "old");
        String hash = TemplateArchive.sha256(file);
        var commands = new TestCommands(arguments -> "");
        var launcher = new WpsLauncher(WpsLauncher.Platform.WINDOWS, temporary, Map.of(), commands);
        assertEquals(WPS_NOT_FOUND, launcher.openInWps(file, hash, DOCX).status());
        Files.writeString(file, "new");
        assertEquals(FILE_CHANGED, launcher.openInWps(file, hash, DOCX).status());
        Files.delete(file);
        assertEquals(FILE_MISSING, launcher.openInWps(file, hash, DOCX).status());
        Path image = Files.writeString(temporary.resolve("原件.jpg"), "fixture");
        assertEquals(IMAGE_PREVIEW, launcher.openSource(image, TemplateArchive.sha256(image)).status());
        assertTrue(commands.launched.isEmpty());
    }

    private static final class TestCommands implements WpsLauncher.Commands {
        private final Function<List<String>, String> responses;
        private final List<List<String>> launched = new ArrayList<>();
        private boolean fail;

        private TestCommands(Function<List<String>, String> responses) {
            this.responses = responses;
        }

        @Override public String lookup(List<String> arguments) { return responses.apply(arguments); }

        @Override public void start(List<String> arguments) throws IOException {
            if (fail) throw new IOException("controlled launch failure");
            launched.add(List.copyOf(arguments));
        }

        private List<String> last() { return launched.getLast(); }
    }
}
