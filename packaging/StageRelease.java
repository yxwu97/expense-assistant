import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

final class StageRelease {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: StageRelease COMPONENTS PLATFORM INPUT OCR_ROOT MAIN_JAR");
        }
        if (Runtime.version().feature() != 21) {
            throw new IOException("JDK 21 is required.");
        }
        Path componentsFile = Path.of(args[0]);
        String platform = args[1];
        Path input = Path.of(args[2]).toRealPath();
        Path ocrSource = Path.of(args[3]).toRealPath();
        String jarName = args[4];
        JsonNode components = JSON.readTree(componentsFile.toFile());
        JsonNode target = components.path("platforms").required(platform);
        verifyJars(input, jarName, components, target);
        stageOcr(ocrSource, input.resolve("ocr"), target.required("ocr"));
        Files.copy(componentsFile, input.resolve("components.json"));
    }

    private static void verifyJars(Path input, String jarName, JsonNode components, JsonNode target) throws Exception {
        var deps = new HashMap<String, String>();
        addDigests(deps, components.required("javaDependencies"));
        addDigests(deps, target.required("javaDependencies"));
        Set<String> jars;
        try (var staged = Files.list(input)) {
            jars = staged.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".jar") && !name.equals(jarName))
                    .collect(Collectors.toSet());
        }
        if (!jars.equals(deps.keySet())) {
            throw new IOException("Runtime JAR set differs from the component manifest.");
        }
        for (var entry : deps.entrySet()) {
            verifyDigest(input.resolve(entry.getKey()), entry.getValue());
        }
        try (var jar = new JarFile(input.resolve(jarName).toFile())) {
            String template = components.required("bundledTemplate").required("resource").asText();
            var entry = jar.getJarEntry(template);
            if (entry == null || jar.getJarEntry("expenseassistant/DesktopLauncher.class") == null) {
                throw new IOException("App JAR is missing its launcher or company template.");
            }
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(jar.getInputStream(entry), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            String expected = components.required("bundledTemplate").required("sha256").asText();
            if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("App JAR contains an unexpected company template.");
            }
        }
    }

    private static void stageOcr(Path source, Path destination, JsonNode ocr) throws Exception {
        var files = new HashMap<String, String>();
        addDigests(files, ocr.required("files"));
        String executable = ocr.required("executable").asText();
        String tessdata = "share/tessdata";
        if (!files.keySet().containsAll(List.of(executable, tessdata + "/chi_sim.traineddata",
                tessdata + "/eng.traineddata", tessdata + "/configs/tsv"))) {
            throw new IOException("OCR release manifest is incomplete.");
        }
        for (var entry : files.entrySet()) {
            Path relative = Path.of(entry.getKey());
            if (relative.isAbsolute() || !relative.equals(relative.normalize()) || relative.startsWith("..")) {
                throw new IOException("Invalid OCR component path " + relative);
            }
            Path file = source.resolve(relative).toRealPath();
            if (!file.startsWith(source)) {
                throw new IOException("OCR component escapes its provisioned root " + relative);
            }
            Path staged = destination.resolve(relative);
            Files.createDirectories(staged.getParent());
            Files.copy(file, staged, StandardCopyOption.COPY_ATTRIBUTES);
            verifyDigest(staged, entry.getValue());
        }
        Path program = destination.resolve(executable);
        String actualVersion = instructions(program, List.of("--version"));
        if (!actualVersion.lines().findFirst().orElse("").equals("tesseract " + ocr.required("tesseract").asText())
                || !actualVersion.contains("leptonica-" + ocr.required("leptonica").asText())) {
            throw new IOException("OCR executable version differs from the manifest.");
        }
        var languages = instructions(program, List.of("--tessdata-dir", destination.resolve(tessdata).toString(),
                "--list-langs")).lines().map(String::strip).collect(Collectors.toSet());
        if (!languages.containsAll(Set.of("chi_sim", "eng"))) {
            throw new IOException("Staged OCR cannot find the Chinese and English language data.");
        }
    }

    private static void addDigests(Map<String, String> target, JsonNode object) {
        object.properties().forEach(entry -> {
            String digest = entry.getValue().asText();
            if (!digest.matches("[a-f0-9]{64}") || target.putIfAbsent(entry.getKey(), digest) != null) {
                throw new IllegalArgumentException("Invalid or repeated component " + entry.getKey());
            }
        });
    }

    private static void verifyDigest(Path file, String expected) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(file), digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new IOException("Component checksum differs for " + file.getFileName());
        }
    }

    private static String instructions(Path executable, List<String> args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(executable.toString());
        command.addAll(args);
        var builder = new ProcessBuilder(command).redirectErrorStream(true).directory(executable.getParent().toFile());
        var environment = builder.environment();
        environment.remove("TESSDATA_PREFIX");
        environment.keySet().removeIf(key -> key.startsWith("DYLD_"));
        String systemDirectory = environment.get("SystemRoot");
        environment.put("PATH", systemDirectory == null ? "/usr/bin:/bin" : Path.of(systemDirectory, "System32").toString());
        Path log = Files.createTempFile(executable.getParent(), "preflight-", ".txt");
        try {
            Process process = builder.redirectOutput(log.toFile()).start();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw new IOException("Staged OCR could not execute " + args.getLast());
                }
                return Files.readString(log);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
