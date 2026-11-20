package expenseassistant.store;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

public final class Database {
    private final Path file;

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }

    private Database(Path file) {
        this.file = file;
    }

    public static Database open(Path databaseFile) {
        return open(databaseFile, new SchemaMigrator());
    }

    static Database open(Path databaseFile, SchemaMigrator migrator) {
        Path file = databaseFile.toAbsolutePath().normalize();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("数据路径必须是普通文件");
            }
            migrator.migrate(file);
            return new Database(file);
        } catch (IOException | SQLException error) {
            throw new StoreException("STORE_UNAVAILABLE", "无法打开报销数据库", file, error);
        }
    }

    public Path file() {
        return file;
    }

    public <T> T read(SqlWork<T> work) {
        return run(work, true);
    }

    public <T> T transaction(SqlWork<T> work) {
        return run(work, false);
    }

    private <T> T run(SqlWork<T> work, boolean readOnly) {
        try (Connection connection = connect(file, readOnly, false)) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception | Error error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    error.addSuppressed(rollbackFailure);
                }
                throw error;
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new StoreException("STORE_UNAVAILABLE", "数据库事务执行失败", file, error);
        }
    }

    static Connection connect(Path file, boolean readOnly, boolean create) throws SQLException {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        config.setReadOnly(readOnly);
        if (!create) {
            config.resetOpenMode(SQLiteOpenMode.CREATE);
        }
        config.setTransactionMode(readOnly ? SQLiteConfig.TransactionMode.DEFERRED : SQLiteConfig.TransactionMode.IMMEDIATE);
        String url = "jdbc:sqlite:" + file.toUri().toASCIIString();
        return config.createConnection(url);
    }
}
