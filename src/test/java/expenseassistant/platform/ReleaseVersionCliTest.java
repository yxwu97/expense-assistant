package expenseassistant.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVersionCliTest {
    private static final String RELEASE = "1.0.20240229001";
    private static final String ACTIVE_ENTRY = RELEASE + "=1.0.2,active\n";
    @TempDir Path temporary;

    @Test
    void activeLeapDayReleasePrintsOnlyTheNativeVersion() throws Exception {
        var result = run("# An earlier abandoned version remains reserved\n" +
                "1.0.20240228001=1.0.1,abandoned\n" + ACTIVE_ENTRY, RELEASE);
        assertEquals(0, result.status());
        assertEquals("1.0.2" + System.lineSeparator(), result.output());
        assertEquals("", result.error());
    }

    @Test
    void currentBuildVersionIsRegistered() throws Exception {
        var result = run(Path.of("packaging", "release-versions.properties"), AppVersion.value().orElseThrow());
        assertEquals(0, result.status(), result.error());
    }

    @ParameterizedTest
    @MethodSource("invalidVersions")
    void rejectsInvalidOrUnavailableVersion(String entries, String input, String error) throws Exception {
        var result = run(entries, input);
        assertNotEquals(0, result.status());
        assertEquals("", result.output());
        assertTrue(result.error().contains(error), result.error());
    }

    private static Stream<Arguments> invalidVersions() {
        return Stream.of(
                Arguments.of(ACTIVE_ENTRY, "1.0.20230229001", "Invalid release date"),
                Arguments.of(ACTIVE_ENTRY, "1.0.20240229000", "Invalid application version or release sequence"),
                Arguments.of(ACTIVE_ENTRY, "1.0.20240229002", "not registered"),
                Arguments.of(ACTIVE_ENTRY + RELEASE + "=1.0.3,active\n", RELEASE, "Duplicate application"),
                Arguments.of(ACTIVE_ENTRY + "1.0.20240229002=1.0.2,active\n", RELEASE, "unique and increasing"),
                Arguments.of(ACTIVE_ENTRY + "1.0.20240229002=1.0.1,active\n", RELEASE, "unique and increasing"),
                Arguments.of(ACTIVE_ENTRY + "1.0.20240229002=256.0.0,active\n", RELEASE, "255.99.99"),
                Arguments.of(RELEASE + "=1.100.0,active\n", RELEASE, "255.99.99"),
                Arguments.of(RELEASE + "=1.0.100,active\n", RELEASE, "255.99.99"),
                Arguments.of(RELEASE + "=0.0.1,active\n", RELEASE, "255.99.99"),
                Arguments.of(RELEASE + "=1.0.2,abandoned\n", RELEASE, "abandoned"));
    }

    private Result run(String content, String version) throws Exception {
        return run(Files.writeString(temporary.resolve("release ledger.properties"), content), version);
    }

    private Result run(Path ledger, String version) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path stdout = temporary.resolve("stdout");
        Path stderr = temporary.resolve("stderr");
        var process = new ProcessBuilder(java.toString(), Path.of("packaging", "ReleaseVersion.java").toString(),
                ledger.toString(), version).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "ReleaseVersion did not exit");
            return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private record Result(int status, String output, String error) {}
}
