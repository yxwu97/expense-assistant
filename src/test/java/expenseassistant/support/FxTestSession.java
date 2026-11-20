package expenseassistant.support;

import javafx.application.Platform;
import org.opentest4j.TestAbortedException;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FxTestSession {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static CompletableFuture<Void> startup;

    private FxTestSession() {}

    public static void runOnFxThread(Runnable action) throws Exception {
        runOnFxThread(() -> {
            action.run();
            return null;
        });
    }

    public static <T> T runOnFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }
        startOnce();
        var work = new FutureTask<>(action);
        Platform.runLater(work);
        try {
            return work.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (error.getCause() instanceof Error cause) {
                throw cause;
            }
            throw error;
        }
    }

    private static synchronized void startOnce() throws InterruptedException {
        if (startup == null) {
            var ready = new CompletableFuture<Void>();
            startup = ready;
            Thread.ofPlatform().daemon().name("expense-test-fx-startup").start(() -> {
                try {
                    Platform.startup(() -> {
                        Platform.setImplicitExit(false);
                        ready.complete(null);
                    });
                } catch (IllegalStateException alreadyRunning) {
                    Platform.runLater(() -> ready.complete(null));
                } catch (RuntimeException | LinkageError unavailable) {
                    ready.completeExceptionally(unavailable);
                }
            });
        }
        try {
            startup.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException unavailable) {
            throw new TestAbortedException("桌面测试环境受阻，无法启动 JavaFX 图形会话", unavailable.getCause());
        } catch (TimeoutException unavailable) {
            startup.completeExceptionally(unavailable);
            throw new TestAbortedException("桌面测试环境受阻，JavaFX 图形会话未就绪", unavailable);
        }
    }
}
