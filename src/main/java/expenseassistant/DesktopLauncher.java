package expenseassistant;

import expenseassistant.platform.AppPaths;

/** Entry point for the bundled classpath-based JavaFX application. */
public final class DesktopLauncher {
    private DesktopLauncher() {}

    public static void main(String[] args) {
        System.getProperties().putIfAbsent("javafx.cachedir",
                AppPaths.resolve().cacheDirectory().resolve("javafx").toString());
        ExpenseAssistantApp.main(args);
    }
}
