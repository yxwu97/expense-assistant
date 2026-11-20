package expenseassistant.store;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class LockProbe {
    private LockProbe() {}

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        try (var lock = InstanceLock.acquire(data)) {
            Database database = Database.open(data.resolve("probe.sqlite"));
            recover(database);
            System.out.println("READY");
            System.out.flush();
            new CountDownLatch(1).await();
        } catch (StoreException error) {
            System.out.println(error.code());
            System.exit(2);
        }
    }

    static void recover(Database database) {
        database.transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS lock_recovery_fixture (id INTEGER PRIMARY KEY, restored INTEGER NOT NULL)");
                statement.execute("INSERT OR IGNORE INTO lock_recovery_fixture (id, restored) VALUES (1, 0)");
                statement.executeUpdate("UPDATE lock_recovery_fixture SET restored = restored + 1 WHERE restored = 0");
            }
            return null;
        });
    }
}
