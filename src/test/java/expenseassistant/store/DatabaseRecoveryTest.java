package expenseassistant.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import expenseassistant.output.TemplateArchive;
import expenseassistant.platform.AppVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRecoveryTest {
    @TempDir Path temporary;

    @Test
    void repeatableStartupAndDatabaseConstraints() throws Exception {
        Path data = temporary.resolve("隔离 数据");
        var file = data.resolve("报销 ? 草稿.sqlite");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            file = data.resolve("报销 草稿.sqlite");
        }
        try (var lock = InstanceLock.acquire(data)) {
            var database = Database.open(file);
            assertEquals("INSTANCE_LOCKED", assertThrows(StoreException.class, () -> InstanceLock.acquire(data)).code());
            Process blocked = probe(data);
            try {
                assertEquals("INSTANCE_LOCKED", firstSignal(blocked));
                assertTrue(blocked.waitFor(10, TimeUnit.SECONDS));
                assertEquals(2, blocked.exitValue());
            } finally {
                kill(blocked);
            }
            seed(database);
            assertEquals(Integer.valueOf(1), database.read(connection -> integer(connection, "PRAGMA foreign_keys")));
            assertThrows(StoreException.class, () -> database.read(connection -> execute(connection,
                    "UPDATE reimbursement_case SET revision=7")));
            assertThrows(StoreException.class, () -> database.transaction(connection -> execute(connection,
                    "INSERT INTO source_document (doc_id,case_id,sha256,format) VALUES ('doc','absent','xyz','PDF')")));
            assertThrows(StoreException.class, () -> database.transaction(connection -> {
                execute(connection, "INSERT INTO source_document (doc_id,case_id,sha256,format) VALUES ('doc-1','case-1','hash','PDF')");
                return execute(connection, "INSERT INTO source_document (doc_id,case_id,sha256,format) VALUES ('doc-2','case-1','hash','PDF')");
            }));
            assertEquals(Integer.valueOf(0), database.read(connection -> integer(connection, "SELECT COUNT(*) FROM source_document")));
            assertThrows(StoreException.class, () -> database.transaction(connection -> execute(connection,
                    "INSERT INTO charge_claim VALUES ('case-2','invoice','item-1','RAIL',100)")));
            assertThrows(StoreException.class, () -> database.transaction(connection -> {
                insertActiveExport(connection, "job-a", "request-a", "GENERATING");
                insertActiveExport(connection, "job-b", "request-b", "READY_TO_PUBLISH");
                return null;
            }));
        }
        try (var lock = InstanceLock.acquire(data)) {
            var reopened = Database.open(file);
            assertEquals(1, new SchemaMigrator().verify(file));
            assertEquals(Integer.valueOf(1), reopened.read(connection -> integer(connection,
                    "SELECT COUNT(*) FROM export_record")));
        }
    }

    @Test
    void separateJvmCannotWriteAndForcedExitReleasesLockOnlyOnce() throws Exception {
        Path data = temporary.resolve("锁探针 工作区");
        Process holder = probe(data);
        try {
            assertEquals("READY", firstSignal(holder));
            assertEquals("INSTANCE_LOCKED", assertThrows(StoreException.class, () -> InstanceLock.acquire(data)).code());
            Process rejected = probe(data);
            try {
                assertEquals("INSTANCE_LOCKED", firstSignal(rejected));
                assertTrue(rejected.waitFor(10, TimeUnit.SECONDS));
                assertEquals(2, rejected.exitValue());
            } finally {
                kill(rejected);
            }
        } finally {
            kill(holder);
        }
        assertTrue(Files.isRegularFile(data.resolve(".expense-assistant.lock")));
        try (var lock = InstanceLock.acquire(data)) {
            var database = Database.open(data.resolve("probe.sqlite"));
            LockProbe.recover(database);
            assertEquals(Integer.valueOf(1), database.read(connection -> integer(connection,
                    "SELECT restored FROM lock_recovery_fixture WHERE id = 1")));
        }
    }

    @Test
    void laterSchemaRemainsUntouched() throws Exception {
        var database = Database.open(temporary.resolve("newer.sqlite"));
        database.transaction(connection -> execute(connection, "PRAGMA user_version = 99"));
        String digest = TemplateArchive.sha256(database.file());
        var failure = assertThrows(StoreException.class, () -> Database.open(database.file()));
        assertEquals("SCHEMA_TOO_NEW", failure.code());
        assertEquals(99, new SchemaMigrator().verify(database.file()));
        assertEquals(digest, TemplateArchive.sha256(database.file()));
        assertFalse(Files.exists(temporary.resolve("backups")));
    }

    @Test
    void verifiesFailedMigrationRollbackThenUpgradesFromSameOldDatabase() throws Exception {
        var database = Database.open(temporary.resolve("rollback.sqlite"));
        seed(database);
        var failing = revisionTwo((phase, path) -> {
            if (phase == SchemaMigrator.Phase.BEFORE_COMMIT) {
                throw new SQLException("故障注入：提交前");
            }
        });
        var failure = assertThrows(StoreException.class, () -> Database.open(database.file(), failing));
        assertEquals("MIGRATION_ROLLED_BACK", failure.code());
        assertTrue(Files.isRegularFile(failure.backup()));
        assertEquals(1, failing.verify(database.file()));
        assertSeed(Database.open(database.file()));
        var succeeding = revisionTwo((phase, path) -> {});
        assertSeed(Database.open(database.file(), succeeding));
        assertEquals(2, succeeding.verify(database.file()));
    }

    @Test
    void retainsFailedNewSchemaAndRestoresVerifiedBackupSeparately() throws Exception {
        var database = Database.open(temporary.resolve("restore.sqlite"));
        seed(database);
        var failing = revisionTwo((phase, path) -> {
            if (phase == SchemaMigrator.Phase.AFTER_COMMIT) {
                try (var connection = Database.connect(path, false, false)) {
                    execute(connection, "UPDATE expense_amount SET confirmed_cny_minor = 700");
                }
            }
        });
        var failure = assertThrows(StoreException.class, () -> Database.open(database.file(), failing));
        assertEquals("MIGRATION_RESTORE_REQUIRED", failure.code());
        assertEquals(2, failing.verify(database.file()));
        assertEquals("MIGRATION_RESTORE_REQUIRED", assertThrows(StoreException.class,
                () -> Database.open(database.file(), revisionTwo((phase, path) -> {}))).code());
        Path snapshot = failure.backup();
        assertNotNull(snapshot);
        String stem = snapshot.getFileName().toString().replace(".sqlite", "");
        var backup = new SchemaMigrator.Backup(snapshot, snapshot.resolveSibling(stem + ".manifest.json"));
        var manifest = new ObjectMapper().readValue(backup.manifest().toFile(), SchemaMigrator.BackupManifest.class);
        assertEquals(AppVersion.value().orElseThrow(), manifest.appVersion());
        assertThrows(java.io.IOException.class, () -> failing.restore(backup, database.file()));
        var restored = failing.restore(backup, temporary.resolve("restored-only.sqlite"));
        assertSeed(Database.open(restored));
        assertEquals(1, failing.verify(restored));
        assertEquals(2, failing.verify(database.file()));
    }

    private SchemaMigrator revisionTwo(SchemaMigrator.MigrationProbe hook) {
        return new SchemaMigrator(List.of(new SchemaMigrator.Migration(1, SchemaMigrator.initialStatements()),
                new SchemaMigrator.Migration(2, List.of("ALTER TABLE expense_item ADD COLUMN migrated TEXT"))), hook);
    }

    private static void seed(Database database) {
        database.transaction(connection -> {
            String template = TemplateArchive.BUNDLED_SHA256;
            execute(connection, "INSERT INTO reimbursement_case (id,batch_real_path,case_real_path,batch_path_key,case_path_key,type,type_origin,revision,template_hash,rule_version) "
                    + "VALUES ('case-1','/batch','/batch/去程','/batch','/batch/去程','TRAVEL','MANUAL',3,'" + template + "','1')");
            execute(connection, "INSERT INTO reimbursement_case (id,batch_real_path,case_real_path,batch_path_key,case_path_key,rule_version) "
                    + "VALUES ('case-2','/batch','/batch/返程','/batch','/batch/返程','1')");
            execute(connection, "INSERT INTO expense_item (id,case_id,subtype,stable_order) VALUES ('item-1','case-1','RAIL',1)");
            execute(connection, "INSERT INTO expense_amount (case_id,item_id,component,confirmed_cny_minor,basis) VALUES ('case-1','item-1','RAIL',15879,'确认')");
            execute(connection, "INSERT INTO field_override (case_id,target_id,field_key,action,value,updated_revision) "
                    + "VALUES ('case-1','item-1','DESCRIPTION','SET','已补录',3)");
            execute(connection, "INSERT INTO export_record (export_id,case_id,revision,request_id,request_fingerprint,rule_version,template_hash,snapshot_json,state,staging_path,final_path) "
                    + "VALUES ('export-1','case-1',3,'request-1','fingerprint','1','" + template + "','{}','SUCCEEDED','/out/stage','/out/final')");
            execute(connection, "INSERT INTO export_file VALUES ('export-1','XLSX','清单.xlsx','excel-sha',928)");
            execute(connection, "INSERT INTO export_file VALUES ('export-1','DOCX','附件.docx','word-sha',621)");
            execute(connection, "INSERT INTO charge_claim VALUES ('case-1','invoice','item-1','RAIL',15879)");
            execute(connection, "UPDATE reimbursement_case SET current_export_id='export-1' WHERE id='case-1'");
            return null;
        });
    }

    private static void assertSeed(Database database) {
        database.read(connection -> {
            assertEquals(3, integer(connection, "SELECT revision FROM reimbursement_case WHERE id='case-1'"));
            assertEquals(15879, integer(connection, "SELECT confirmed_cny_minor FROM expense_amount WHERE item_id='item-1'"));
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM field_override WHERE value='已补录'"));
            assertEquals(2, integer(connection, "SELECT COUNT(*) FROM export_file WHERE sha256 IN ('excel-sha','word-sha')"));
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM export_record WHERE state='SUCCEEDED' AND template_hash='"
                    + TemplateArchive.BUNDLED_SHA256 + "'"));
            return null;
        });
    }

    private static void insertActiveExport(Connection connection, String id, String request, String state) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO export_record (export_id,case_id,revision,request_id,request_fingerprint,rule_version,template_hash,snapshot_json,state,staging_path,final_path) "
                + "VALUES (?,'case-1',3,?,'fingerprint','1','template','{}',?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, request);
            statement.setString(3, state);
            statement.setString(4, "stage-" + id);
            statement.setString(5, "final-" + id);
            statement.execute();
        }
    }

    private static int execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static int integer(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static Process probe(Path directory) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String testClasspath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java.toString(), "-cp", testClasspath, LockProbe.class.getName(), directory.toString())
                .redirectErrorStream(true).start();
    }

    private static String firstSignal(Process process) throws Exception {
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var signal = worker.submit(() -> {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (Set.of("READY", "INSTANCE_LOCKED").contains(line)) {
                        return line;
                    }
                }
                return "PROBE_FAILED";
            });
            try {
                return signal.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                kill(process);
                throw timeout;
            }
        }
    }

    private static void kill(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
}
