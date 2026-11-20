package expenseassistant;

import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.draft.DraftService;
import expenseassistant.intake.PathPolicy;
import expenseassistant.intake.ScanService;
import expenseassistant.output.ExportService;
import expenseassistant.output.TemplateArchive;
import expenseassistant.output.TemplateCatalog;
import expenseassistant.platform.AppPaths;
import expenseassistant.platform.WpsLauncher;
import expenseassistant.recognition.RecognitionService;
import expenseassistant.store.Database;
import expenseassistant.store.InstanceLock;
import expenseassistant.store.StoreException;
import expenseassistant.ui.Workbench;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ExpenseAssistantApp extends Application {
    private final ExecutorService startup = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("expense-startup").factory());
    private InstanceLock instanceLock;
    private Workbench workbench;
    private boolean stopped;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        // PDFBox uses AWT drawing without creating a second desktop toolkit.
        System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void start(Stage stage) {
        var paths = AppPaths.resolve();
        var heading = new Label("报销助手");
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        var status = new Label();
        status.setWrapText(true);
        var retry = new Button("重新初始化");
        var selectTemplate = new Button("选择匹配的公司模板");
        retry.setOnAction(event -> initialize(stage, paths, status, retry, selectTemplate, null));
        selectTemplate.setOnAction(event -> {
            var chooser = new FileChooser();
            chooser.setTitle("选择匹配的公司模板");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel 模板 (*.xlsx)", "*.xlsx"));
            var selected = chooser.showOpenDialog(stage);
            if (selected != null) {
                initialize(stage, paths, status, retry, selectTemplate, selected.toPath());
            }
        });
        var content = new VBox(14, heading, status, retry, selectTemplate);
        content.setPadding(new Insets(28));
        stage.setTitle("报销助手");
        stage.getIcons().add(new Image(ExpenseAssistantApp.class.getResource("/ui/expense-assistant.png").toExternalForm()));
        stage.setScene(new Scene(content));
        placeStartupWindow(stage, currentScreen(stage), 620, 260, 0, 0);
        stage.show();
        initialize(stage, paths, status, retry, selectTemplate, null);
    }

    private void initialize(Stage stage, AppPaths paths, Label status, Button retry, Button selectTemplate, Path replacement) {
        status.setText("正在准备应用数据…");
        showButton(retry, false);
        showButton(selectTemplate, false);
        startup.execute(() -> {
            InstanceLock candidate = null;
            try {
                candidate = InstanceLock.acquire(paths.dataDirectory());
                Files.createDirectories(paths.cacheDirectory());
                var templates = new TemplateArchive(paths.dataDirectory());
                if (replacement != null) {
                    templates.locate(TemplateArchive.BUNDLED_SHA256, replacement);
                }
                templates.installBundled();
                var database = Database.open(paths.databaseFile());
                var jobs = new CaseJobs();
                var policy = new PathPolicy(database);
                var cases = new CaseService(database, policy);
                var scans = new ScanService(cases, policy, jobs);
                var drafts = new DraftService(database, jobs, RecognitionService.fromPaths(paths));
                var catalog = new TemplateCatalog(templates);
                var exports = new ExportService(database, jobs, policy, catalog, paths);
                exports.recover();
                if (adopt(candidate)) {
                    candidate = null;
                    Platform.runLater(() -> {
                        if (stopped) return;
                        var screen = currentScreen(stage);
                        workbench = new Workbench(cases, scans, drafts, new WpsLauncher(), exports, templates, catalog);
                        stage.setScene(new Scene(workbench));
                        placeStartupWindow(stage, screen, 1090, 760, 520, 420);
                        stage.setOnCloseRequest(event -> {
                            if (!workbench.canClose()) event.consume();
                        });
                        stage.focusedProperty().addListener((observable, before, focused) -> {
                            if (focused) workbench.refreshOutputs();
                        });
                    });
                }
            } catch (Exception error) {
                Platform.runLater(() -> {
                    String code = switch (error) {
                        case StoreException storeError -> storeError.code();
                        case TemplateArchive.TemplateMismatch mismatch -> mismatch.code();
                        default -> "初始化失败";
                    };
                    status.setText("无法初始化 " + paths.dataDirectory() + "\n" + code + " " + error.getMessage());
                    showButton(retry, true);
                    showButton(selectTemplate, error instanceof TemplateArchive.TemplateMismatch);
                });
            } finally {
                release(candidate);
            }
        });
    }

    private static Screen currentScreen(Stage stage) {
        var screens = Screen.getScreens();
        var primary = Screen.getPrimary();
        int index = StartupWindow.selectScreen(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                screens.stream().map(Screen::getBounds).toList(), screens.indexOf(primary));
        return screens.get(index);
    }

    private static void placeStartupWindow(Stage stage, Screen screen, double width, double height,
                                           double minWidth, double minHeight) {
        var placement = StartupWindow.center(screen.getVisualBounds(), width, height, minWidth, minHeight);
        stage.setWidth(placement.width());
        stage.setHeight(placement.height());
        stage.setX(placement.x());
        stage.setY(placement.y());
        stage.setMinWidth(placement.minWidth());
        stage.setMinHeight(placement.minHeight());
    }

    private static void showButton(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
    }

    private synchronized boolean adopt(InstanceLock candidate) {
        if (stopped) {
            return false;
        }
        instanceLock = candidate;
        return true;
    }

    private static void release(InstanceLock lock) {
        if (lock != null) {
            try {
                lock.close();
            } catch (IOException error) {
                System.getLogger(ExpenseAssistantApp.class.getName()).log(System.Logger.Level.WARNING,
                        "INSTANCE_LOCK_RELEASE_FAILED");
            }
        }
    }

    @Override
    public synchronized void stop() {
        stopped = true;
        if (workbench != null) {
            workbench.close();
        }
        startup.shutdown();
        release(instanceLock);
    }
}
