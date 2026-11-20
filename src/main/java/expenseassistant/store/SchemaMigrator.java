package expenseassistant.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import expenseassistant.output.TemplateArchive;
import expenseassistant.platform.AppVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SchemaMigrator {
    private static final String INITIAL_RESOURCE = "/db/V001__initial.sql";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> BUSINESS_CHECKS = Map.of(
            "reimbursement_case", "SELECT id, revision, type, type_origin, template_hash, rule_version, current_export_id "
                    + "FROM reimbursement_case ORDER BY id",
            "field_override", "SELECT case_id, target_id, field_key, action, value, source_hashes, updated_revision "
                    + "FROM field_override ORDER BY case_id, target_id, field_key",
            "expense_amount", "SELECT case_id, item_id, component, face_value, original_currency, confirmed_cny_minor, basis "
                    + "FROM expense_amount ORDER BY case_id, item_id, component",
            "export_record", "SELECT export_id, case_id, revision, template_hash, state, snapshot_json "
                    + "FROM export_record ORDER BY export_id",
            "export_file", "SELECT export_id, kind, file_name, sha256, size FROM export_file ORDER BY export_id, kind");

    private final List<Migration> migrations;
    private final MigrationProbe probe;

    public SchemaMigrator() {
        this(List.of(new Migration(1, initialStatements())), (stage, file) -> {});
    }

    SchemaMigrator(List<Migration> migrations, MigrationProbe probe) {
        this.migrations = List.copyOf(migrations);
        this.probe = probe;
        for (int index = 0; index < migrations.size(); index++) {
            if (migrations.get(index).version() != index + 1) {
                throw new IllegalArgumentException("数据库迁移版本必须连续");
            }
        }
    }

    public void migrate(Path file) throws IOException, SQLException {
        PendingMigration pending = readPending(file);
        Backup interruptedBackup = pending == null ? null : pending.backup(file);
        boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        int oldVersion;
        try {
            oldVersion = exists ? verify(file) : 0;
        } catch (SQLException invalidDatabase) {
            if (interruptedBackup != null) {
                throw recoveryRequired(file, interruptedBackup, invalidDatabase);
            }
            throw invalidDatabase;
        }
        int desiredVersion = migrations.size();
        if (oldVersion > desiredVersion) {
            throw new StoreException("SCHEMA_TOO_NEW", "数据库由较新版本创建，请使用对应的程序", file, null);
        }
        if (pending != null) {
            resolveInterruptedMigration(file, pending, interruptedBackup, oldVersion);
        }
        if (oldVersion == desiredVersion) {
            checkCurrentSchema(file);
            return;
        }
        if (exists && oldVersion == 0) {
            checkUnversionedDatabaseIsEmpty(file);
        }
        Backup backup = oldVersion == 0 ? null : backup(file, file.getParent().resolve("backups"));
        if (backup != null) {
            writePending(file, new PendingMigration(oldVersion, desiredVersion, backup.database().getFileName().toString()));
        }

        boolean committed = false;
        try {
            try (Connection connection = Database.connect(file, false, !exists)) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    for (Migration migration : migrations.subList(oldVersion, desiredVersion)) {
                        for (String sql : migration.statements()) {
                            statement.execute(sql);
                        }
                        statement.execute("PRAGMA user_version = " + migration.version());
                    }
                    checkForeignKeys(connection);
                    checkManifest(connection, backup);
                    probe.check(Phase.BEFORE_COMMIT, file);
                    connection.commit();
                    committed = true;
                } catch (Exception error) {
                    if (!committed) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackFailure) {
                            error.addSuppressed(rollbackFailure);
                        }
                    }
                    throw error;
                }
            }
            probe.check(Phase.AFTER_COMMIT, file);
            verifyAgainst(file, desiredVersion, backup);
            Files.deleteIfExists(pendingPath(file));
        } catch (Exception error) {
            if (!committed) {
                try {
                    verifyAgainst(file, oldVersion, backup);
                    Files.deleteIfExists(pendingPath(file));
                } catch (IOException | SQLException verificationFailure) {
                    error.addSuppressed(verificationFailure);
                    throw recoveryRequired(file, backup, error);
                }
                throw new StoreException("MIGRATION_ROLLED_BACK", "升级已回滚并核验原数据库，请使用原版本重开",
                        file, backup == null ? null : backup.database(), error);
            }
            throw recoveryRequired(file, backup, error);
        }
    }

    public Backup backup(Path source, Path backupDirectory) throws IOException, SQLException {
        Files.createDirectories(backupDirectory);
        Path staged = Files.createTempFile(backupDirectory, "schema-backup-", ".tmp");
        try {
            int version;
            Map<String, TableCheck> before;
            try (var connection = Database.connect(source, true, false);
                 var vacuum = connection.prepareStatement("VACUUM INTO ?")) {
                version = schemaVersion(connection);
                before = businessManifest(connection);
                vacuum.setString(1, staged.toAbsolutePath().toString());
                vacuum.execute();
            }
            try (var verified = Database.connect(staged, true, false)) {
                checkIntegrity(verified);
                if (schemaVersion(verified) != version || !before.equals(businessManifest(verified))) {
                    throw new IOException("数据库一致性备份核对失败");
                }
            }
            String stem = "schema-" + version + "-" + UUID.randomUUID();
            Path target = backupDirectory.resolve(stem + ".sqlite");
            Path metadata = backupDirectory.resolve(stem + ".manifest.json");
            var manifest = new BackupManifest(version, appVersion(), Instant.now().toString(),
                    TemplateArchive.sha256(staged), before);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.writeString(metadata, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                        StandardOpenOption.CREATE_NEW);
            } catch (IOException error) {
                Files.deleteIfExists(target);
                throw error;
            }
            return new Backup(target, metadata);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public int verify(Path database) throws SQLException {
        try (var connection = Database.connect(database, true, false)) {
            checkIntegrity(connection);
            return schemaVersion(connection);
        }
    }

    public Path restore(Backup backup, Path separateDestination) throws IOException, SQLException {
        Path destination = separateDestination.toAbsolutePath().normalize();
        if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("恢复位置必须是新文件，故障数据库保留在原处 " + destination);
        }
        BackupManifest expected = readManifest(backup);
        Files.createDirectories(destination.getParent());
        Path staged = Files.createTempFile(destination.getParent(), "schema-restore-", ".tmp");
        try {
            Files.copy(backup.database(), staged, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            if (!expected.sha256().equals(TemplateArchive.sha256(staged))) {
                throw new IOException("备份文件摘要不一致 " + backup.database());
            }
            verifyAgainst(staged, expected.schemaVersion(), backup);
            if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("恢复位置已被使用 " + destination);
            }
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static Path pendingPath(Path file) {
        return file.resolveSibling(file.getFileName() + ".migration.json");
    }

    private static PendingMigration readPending(Path file) throws IOException {
        Path marker = pendingPath(file);
        if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("数据库升级状态文件不可读取 " + marker);
        }
        return JSON.readValue(marker.toFile(), PendingMigration.class);
    }

    private static void writePending(Path file, PendingMigration pending) throws IOException {
        Path staged = Files.createTempFile(file.getParent(), "schema-upgrade-", ".tmp");
        try {
            JSON.writeValue(staged.toFile(), pending);
            Files.move(staged, pendingPath(file), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void resolveInterruptedMigration(Path file, PendingMigration pending, Backup backup, int version) {
        try {
            if (version != pending.oldVersion() && version != pending.targetVersion()) {
                throw new IOException("中断的升级版本与当前数据库不匹配");
            }
            BackupManifest manifest = readManifest(backup);
            if (manifest.schemaVersion() != pending.oldVersion()
                    || !manifest.sha256().equals(TemplateArchive.sha256(backup.database()))) {
                throw new IOException("中断的升级备份核对失败");
            }
            verifyAgainst(file, version, backup);
            Files.delete(pendingPath(file));
        } catch (IOException | SQLException error) {
            throw recoveryRequired(file, backup, error);
        }
    }

    static List<String> initialStatements() {
        try (var resource = SchemaMigrator.class.getResourceAsStream(INITIAL_RESOURCE)) {
            if (resource == null) {
                throw new IOException("缺少初始数据库迁移 " + INITIAL_RESOURCE);
            }
            return Arrays.stream(new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";\\s*(?:\\R|$)"))
                    .map(String::strip).filter(sql -> !sql.isEmpty()).toList();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void checkUnversionedDatabaseIsEmpty(Path file) throws SQLException {
        try (var connection = Database.connect(file, true, false);
             var statement = connection.createStatement();
             var tables = statement.executeQuery("SELECT 1 FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' LIMIT 1")) {
            if (tables.next()) {
                throw new StoreException("SCHEMA_UNRECOGNIZED", "数据库含有未标记版本的结构，请核对数据目录", file, null);
            }
        }
    }

    private static void checkCurrentSchema(Path file) throws SQLException {
        try (var connection = Database.connect(file, true, false); var statement = connection.createStatement()) {
            for (String query : BUSINESS_CHECKS.values()) {
                try (var empty = statement.executeQuery(query + " LIMIT 0")) {
                    if (empty.next()) {
                        throw new SQLException("数据库结构检查失败");
                    }
                }
            }
        }
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) {
                throw new SQLException("无法读取数据库版本");
            }
            return result.getInt(1);
        }
    }

    private static void checkIntegrity(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) {
                throw new SQLException("数据库完整性检查失败");
            }
        }
        checkForeignKeys(connection);
    }

    private static void checkForeignKeys(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (result.next()) {
                throw new SQLException("数据库外键检查失败，表 " + result.getString(1));
            }
        }
    }

    private static void verifyAgainst(Path file, int version, Backup backup) throws IOException, SQLException {
        try (var connection = Database.connect(file, true, false)) {
            checkIntegrity(connection);
            if (schemaVersion(connection) != version) {
                throw new SQLException("数据库版本核对失败");
            }
            checkManifest(connection, backup);
        }
    }

    private static void checkManifest(Connection connection, Backup backup) throws IOException, SQLException {
        if (backup != null && !readManifest(backup).tables().equals(businessManifest(connection))) {
            throw new SQLException("草稿、补录、已确认分项或历史成品登记与备份不一致");
        }
    }

    private static BackupManifest readManifest(Backup backup) throws IOException {
        return JSON.readValue(backup.manifest().toFile(), BackupManifest.class);
    }

    private static Map<String, TableCheck> businessManifest(Connection connection) throws SQLException {
        var manifest = new LinkedHashMap<String, TableCheck>();
        for (var table : BUSINESS_CHECKS.entrySet()) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(table.getValue())) {
                var digest = sha256Digest();
                long count = 0;
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    count++;
                    for (int column = 1; column <= columns; column++) {
                        String value = rows.getString(column);
                        if (value == null) {
                            digest.update((byte) '-');
                        } else {
                            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                            digest.update((byte) ':');
                            digest.update(bytes);
                        }
                        digest.update((byte) 0);
                    }
                }
                manifest.put(table.getKey(), new TableCheck(count, HexFormat.of().formatHex(digest.digest())));
            }
        }
        return manifest;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String appVersion() {
        return AppVersion.value().orElse("development");
    }

    private static StoreException recoveryRequired(Path file, Backup backup, Exception error) {
        return new StoreException("MIGRATION_RESTORE_REQUIRED", "数据库升级后未通过核验，请从已验证备份恢复到另一位置",
                file, backup == null ? null : backup.database(), error);
    }

    public record Backup(Path database, Path manifest) {}
    record PendingMigration(int oldVersion, int targetVersion, String databaseName) {
        Backup backup(Path sourceDatabase) throws IOException {
            if (oldVersion < 1 || targetVersion <= oldVersion || databaseName == null
                    || !databaseName.matches("schema-" + oldVersion + "-[0-9a-f-]{36}\\.sqlite")) {
                throw new IOException("数据库升级状态文件内容无效");
            }
            Path database = sourceDatabase.getParent().resolve("backups").resolve(databaseName);
            String stem = databaseName.substring(0, databaseName.length() - ".sqlite".length());
            return new Backup(database, database.resolveSibling(stem + ".manifest.json"));
        }
    }
    record BackupManifest(int schemaVersion, String appVersion, String createdAt, String sha256,
                          Map<String, TableCheck> tables) {}
    record TableCheck(long rows, String sha256) {}
    record Migration(int version, List<String> statements) {}
    enum Phase { BEFORE_COMMIT, AFTER_COMMIT }

    @FunctionalInterface
    interface MigrationProbe {
        void check(Phase phase, Path file) throws Exception;
    }
}
