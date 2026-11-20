package expenseassistant.platform;

import expenseassistant.output.TemplateArchive;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WpsLauncher {
    private static final String MAC_BUNDLE_ID = "com.kingsoft.wpsoffice.mac";
    private static final String APP_PATHS = "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\";
    private final Platform platform;
    private final Path userHome;
    private final Map<String, String> environment;
    private final Commands commands;
    private volatile Path selectedInstallation;

    public enum Component {
        XLSX("et.exe", "WPS 表格", ".xlsx"), DOCX("wps.exe", "WPS 文字", ".docx"),
        PDF("wpspdf.exe", "WPS PDF", ".pdf");

        private final String executable;
        private final String label;
        private final String extension;

        Component(String executable, String label, String extension) {
            this.executable = executable;
            this.label = label;
            this.extension = extension;
        }
    }

    public enum Status { OPENED, IMAGE_PREVIEW, FILE_MISSING, FILE_CHANGED, WPS_NOT_FOUND, LAUNCH_FAILED }
    enum Platform { MAC, WINDOWS, OTHER }

    public record Result(Status status, Path path, String message) {
        public boolean opened() {
            return status == Status.OPENED;
        }
    }

    interface Commands {
        String lookup(List<String> arguments) throws IOException, InterruptedException;
        void start(List<String> arguments) throws IOException, InterruptedException;
    }

    public WpsLauncher() {
        this(detectPlatform(), Path.of(System.getProperty("user.home")), System.getenv(), new SystemCommands());
    }

    WpsLauncher(Platform platform, Path userHome, Map<String, String> environment, Commands commands) {
        this.platform = platform;
        this.userHome = userHome;
        this.environment = Map.copyOf(environment);
        this.commands = commands;
    }

    public void selectInstallation(Path location) {
        selectedInstallation = location == null ? null : location.toAbsolutePath().normalize();
    }

    public Result openSource(Path file, String sha256) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return openInWps(file, sha256, Component.PDF);
        }
        Result issue = verify(file.toAbsolutePath().normalize(), sha256);
        return issue == null ? new Result(Status.IMAGE_PREVIEW, file, "请在工作台查看原图。") : issue;
    }

    public Result openInWps(Path file, String sha256, Component component) {
        Path target = file.toAbsolutePath().normalize();
        Result issue = verify(target, sha256);
        if (issue != null) {
            return issue;
        }
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(component.extension)) {
            return result(Status.LAUNCH_FAILED, target, "文件类型与 " + component.label + " 不匹配。");
        }
        try {
            Path application = switch (platform) {
                case MAC -> findMac();
                case WINDOWS -> findWindows(component);
                case OTHER -> null;
            };
            if (application == null) {
                return result(Status.WPS_NOT_FOUND, target, "未找到 " + component.label + "，可重新选择安装位置。");
            }
            List<String> command = platform == Platform.MAC
                    ? List.of("/usr/bin/open", "-a", application.toString(), target.toString())
                    : List.of(application.toString(), target.toString());
            commands.start(command);
            return new Result(Status.OPENED, target, "已交给 " + component.label + " 打开");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(Status.LAUNCH_FAILED, target, "启动 " + component.label + " 已中断。");
        } catch (IOException | SecurityException failure) {
            return result(Status.LAUNCH_FAILED, target, "无法启动 " + component.label + "。");
        }
    }

    public Result openDirectory(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return result(Status.FILE_MISSING, target, "成品目录当前不可访问。");
        }
        List<String> command = switch (platform) {
            case MAC -> List.of("/usr/bin/open", target.toString());
            case WINDOWS -> List.of(windowsTool("explorer.exe"), target.toString());
            case OTHER -> List.of("xdg-open", target.toString());
        };
        try {
            commands.start(command);
            return new Result(Status.OPENED, target, "已请求打开成品目录");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(Status.LAUNCH_FAILED, target, "打开目录已中断。");
        } catch (IOException | SecurityException failure) {
            return result(Status.LAUNCH_FAILED, target, "无法打开成品目录。");
        }
    }

    private static Result verify(Path target, String sha256) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return result(Status.FILE_MISSING, target, "文件缺失或当前不可访问。");
        }
        try {
            if (sha256 == null || !Objects.equals(sha256, TemplateArchive.sha256(target))) {
                return result(Status.FILE_CHANGED, target, "文件内容已变化，请重新核对。");
            }
            return null;
        } catch (IOException | SecurityException failure) {
            return result(Status.FILE_MISSING, target, "当前无法读取文件。");
        }
    }

    private Path findMac() throws InterruptedException {
        var candidates = new LinkedHashSet<Path>();
        if (selectedInstallation != null) {
            candidates.add(selectedInstallation);
        }
        candidates.add(Path.of("/Applications/wpsoffice.app"));
        candidates.add(userHome.resolve("Applications/wpsoffice.app"));
        for (Path candidate : candidates) {
            if (isWpsBundle(candidate)) {
                return candidate;
            }
        }
        String matches = lookup(List.of("/usr/bin/mdfind", "kMDItemCFBundleIdentifier == '" + MAC_BUNDLE_ID + "'"));
        for (String match : matches.lines().toList()) {
            Path candidate = match.isBlank() ? null : path(match);
            if (candidate != null && candidate.isAbsolute() && !candidates.contains(candidate) && isWpsBundle(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isWpsBundle(Path application) throws InterruptedException {
        Path info = application.resolve("Contents/Info.plist");
        if (!Files.isRegularFile(info)) {
            return false;
        }
        String appId = lookup(plist(info, "CFBundleIdentifier")).strip();
        if (!MAC_BUNDLE_ID.equals(appId)) {
            return false;
        }
        String executable = lookup(plist(info, "CFBundleExecutable")).strip();
        return !executable.isEmpty() && !executable.contains("/") && !executable.contains("\\")
                && Files.isRegularFile(application.resolve("Contents/MacOS").resolve(executable))
                && Files.isExecutable(application.resolve("Contents/MacOS").resolve(executable));
    }

    private static List<String> plist(Path file, String key) {
        return List.of("/usr/bin/plutil", "-extract", key, "raw", "-o", "-", file.toString());
    }

    private Path findWindows(Component component) throws InterruptedException {
        Path selected = selectedInstallation;
        if (selected != null) {
            Path folder = Files.isDirectory(selected) ? selected : selected.getParent();
            if (folder != null) {
                Path candidate = folder.resolve(component.executable);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        Path direct = registeredWindows(component);
        if (direct != null || component == Component.DOCX) return direct;
        Path word = registeredWindows(Component.DOCX);
        if (word != null) {
            Path sibling = word.resolveSibling(component.executable);
            if (Files.isRegularFile(sibling)) return sibling;
        }
        return null;
    }

    private Path registeredWindows(Component component) throws InterruptedException {
        for (String hive : List.of("HKCU", "HKLM")) {
            String key = hive + APP_PATHS + component.executable;
            for (String registryView : List.of("/reg:64", "/reg:32")) {
                String entries = lookup(List.of(windowsTool("System32/reg.exe"), "query", key, "/ve", registryView));
                Path candidate = fromRegistry(entries, component);
                if (candidate != null && Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private Path fromRegistry(String output, Component component) {
        for (String line : output.lines().toList()) {
            String[] pieces = line.split("\\s+REG_(?:EXPAND_)?SZ\\s+", 2);
            if (pieces.length != 2) {
                continue;
            }
            String value = pieces[1].strip();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            for (var env : environment.entrySet()) {
                value = value.replace("%" + env.getKey() + "%", env.getValue());
            }
            Path candidate = path(value);
            if (candidate != null && candidate.isAbsolute() && candidate.getFileName() != null
                    && component.executable.equalsIgnoreCase(candidate.getFileName().toString())) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private String windowsTool(String name) {
        return Path.of(environment.getOrDefault("SystemRoot", "C:\\Windows")).resolve(name).toString();
    }

    private String lookup(List<String> arguments) throws InterruptedException {
        try {
            return commands.lookup(arguments);
        } catch (IOException | SecurityException notAvailable) {
            return "";
        }
    }

    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Result result(Status status, Path target, String message) {
        return new Result(status, target, message + "\n" + target);
    }

    private static Platform detectPlatform() {
        String system = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (system.contains("mac")) return Platform.MAC;
        if (system.startsWith("windows")) return Platform.WINDOWS;
        return Platform.OTHER;
    }

    private static final class SystemCommands implements Commands {
        @Override
        public String lookup(List<String> arguments) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var output = new FutureTask<>(() -> process.getInputStream().readNBytes(256 * 1024));
            Thread.ofVirtual().start(output);
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    return "";
                }
                String encoding = System.getProperty("native.encoding", Charset.defaultCharset().name());
                return new String(output.get(1, TimeUnit.SECONDS), Charset.forName(encoding));
            } catch (ExecutionException | TimeoutException unreadable) {
                return "";
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                output.cancel(true);
            }
        }

        @Override
        public void start(List<String> arguments) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(arguments)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (process.waitFor(800, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
                throw new IOException("WPS launch request was rejected");
            }
        }
    }
}
