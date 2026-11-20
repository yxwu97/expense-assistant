package expenseassistant.platform;

import java.nio.file.Path;
import java.util.Locale;

public record AppPaths(Path installDirectory, Path dataDirectory, Path cacheDirectory, Path defaultOutputRoot) {
    private static final String APPLICATION = "ExpenseAssistant";

    public static AppPaths resolve() {
        var userHome = Path.of(System.getProperty("user.home"));
        var system = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path data;
        Path cache;
        if (system.contains("mac")) {
            data = userHome.resolve("Library/Application Support/" + APPLICATION);
            cache = userHome.resolve("Library/Caches/" + APPLICATION);
        } else if (system.startsWith("windows")) {
            var localAppData = envPath("LOCALAPPDATA", userHome.resolve("AppData/Local"));
            data = localAppData.resolve(APPLICATION).resolve("Data");
            cache = localAppData.resolve(APPLICATION).resolve("Cache");
        } else {
            data = envPath("XDG_DATA_HOME", userHome.resolve(".local/share")).resolve(APPLICATION);
            cache = envPath("XDG_CACHE_HOME", userHome.resolve(".cache")).resolve(APPLICATION);
        }
        var output = userHome.resolve("Documents").resolve("报销助手输出");
        return new AppPaths(
                override("installDirectory", installPath(system)),
                override("dataDirectory", data),
                override("cacheDirectory", cache),
                override("defaultOutputRoot", output));
    }

    public Path databaseFile() {
        return dataDirectory.resolve("expense-assistant.sqlite");
    }

    private static Path installPath(String system) {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null || launcher.isBlank()) {
            return Path.of("");
        }
        var launcherDirectory = Path.of(launcher).toAbsolutePath().getParent();
        if (system.contains("mac")) {
            return launcherDirectory.getParent().resolve("app");
        }
        return launcherDirectory.resolve("app");
    }

    private static Path envPath(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    private static Path override(String name, Path fallback) {
        String value = System.getProperty("expenseassistant." + name);
        return (value == null || value.isBlank() ? fallback : Path.of(value)).toAbsolutePath().normalize();
    }
}
