package expenseassistant.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.draft.DraftService;
import expenseassistant.intake.PathPolicy;
import expenseassistant.platform.AppPaths;
import expenseassistant.platform.ReimburserSettings;
import expenseassistant.recognition.OcrWorkspace;
import expenseassistant.store.Database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ExportService {
    private static final String COLUMNS = "SELECT export_id, case_id, revision, request_id, request_fingerprint, "
            + "retry_of, state, staging_path, final_path, error FROM export_record ";
    private static final String ACTIVE = "('GENERATING','READY_TO_PUBLISH')";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Pattern WINDOWS_RESERVED = Pattern.compile(
            "(?i)^(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$");
    private final Database database;
    private final CaseJobs jobs;
    private final PathPolicy paths;
    private final TemplateCatalog templates;
    private final AppPaths appPaths;
    private final ReimburserSettings settings;
    private final ExportPublisher publisher;
    private final Checkpoint checkpoint;

    public enum State { GENERATING, READY_TO_PUBLISH, SUCCEEDED, FAILED, CONFLICT }
    public enum FileKind { XLSX, DOCX }

    public record PublishedFile(FileKind kind, Path path, String sha256, long size) {}
    public record Record(String exportId, String caseId, long revision, String requestId, String retryOf,
                         State state, Path outputRoot, Path finalDirectory, String errorCode, String message,
                         List<PublishedFile> files) {
        public Record {
            files = List.copyOf(files);
        }

        public PublishedFile file(FileKind kind) {
            return files.stream().filter(file -> file.kind() == kind).findFirst().orElse(null);
        }
    }

    enum Boundary { GENERATING, MANIFEST_COMMITTED, MOVED }
    @FunctionalInterface
    interface Checkpoint {
        void reached(Boundary boundary, Record record);
    }

    private record Attempt(Record record, Path staging, String fingerprint) {}

    public ExportService(Database database, CaseJobs jobs, PathPolicy paths, TemplateCatalog templates, AppPaths appPaths) {
        this(database, jobs, paths, templates, appPaths, new ReimburserSettings(appPaths.dataDirectory()));
    }

    public ExportService(Database database, CaseJobs jobs, PathPolicy paths, TemplateCatalog templates, AppPaths appPaths,
                         ReimburserSettings settings) {
        this(database, jobs, paths, templates, appPaths, settings, ExportPublisher::atomicMove, (boundary, record) -> {});
    }

    ExportService(Database database, CaseJobs jobs, PathPolicy paths, TemplateCatalog templates, AppPaths appPaths,
                  ExportPublisher.Move move, Checkpoint checkpoint) {
        this(database, jobs, paths, templates, appPaths, new ReimburserSettings(appPaths.dataDirectory()), move, checkpoint);
    }

    private ExportService(Database database, CaseJobs jobs, PathPolicy paths, TemplateCatalog templates, AppPaths appPaths,
                          ReimburserSettings settings, ExportPublisher.Move move, Checkpoint checkpoint) {
        this.database = database;
        this.jobs = jobs;
        this.paths = paths;
        this.templates = templates;
        this.appPaths = appPaths;
        this.settings = settings;
        this.publisher = new ExportPublisher(paths, appPaths.cacheDirectory(), templates, move);
        this.checkpoint = checkpoint;
    }

    public Record generate(String caseId, long expectedRevision, Path outputRoot, String requestId) {
        return generate(caseId, expectedRevision, outputRoot, requestId, null);
    }

    public Record generate(String caseId, long expectedRevision, Path outputRoot, String requestId, String retryOf) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 256) {
            throw new CaseException("INVALID_REQUEST_ID", "生成请求号无效");
        }
        var replay = findRequestOrRejectActive(caseId, requestId);
        if (replay != null) {
            return replay(replay, expectedRevision, outputRoot);
        }
        CaseJobs.Job acquired;
        try {
            acquired = jobs.begin(caseId);
        } catch (CaseException busy) {
            var latest = findRequestOrRejectActive(caseId, requestId);
            if (latest != null) {
                return replay(latest, expectedRevision, outputRoot);
            }
            throw busy;
        }
        try (var implicitLock = acquired) {
            var alreadyStarted = database.read(connection -> byRequest(connection, caseId, requestId));
            if (alreadyStarted != null) {
                return replay(alreadyStarted, expectedRevision, outputRoot);
            }
            var view = database.read(connection -> {
                var current = CaseService.load(connection, caseId);
                CaseService.requireNoActivePublication(connection, caseId);
                CaseService.requireRevision(current, expectedRevision);
                verifyRetry(connection, caseId, retryOf);
                return DraftService.read(connection, current);
            });
            var snapshot = ExportSnapshot.fromDraft(view, loadReimburser());
            preflight(snapshot);
            Path requested = outputRoot != null ? outputRoot : preferredRoot(caseId);
            Path root = paths.validateOutputRoot(view.info().batchRoot(), view.info().folder(), requested).path();
            String safeName = safeName(snapshot.caseName());
            var generating = implicitLock.commit(() -> database.transaction(connection -> {
                var current = CaseService.load(connection, caseId);
                CaseService.requireRevision(current, expectedRevision);
                CaseService.requireNoActivePublication(connection, caseId);
                return insert(connection, snapshot, root, requestId, safeName, retryOf);
            }));
            var record = generating.record();
            List<PublishedFile> files;
            try {
                files = publisher.stage(record, generating.staging(), snapshot, safeName,
                        () -> checkpoint.reached(Boundary.GENERATING, record));
            } catch (CaseException failure) {
                return failed(generating, safeName, failure.code(), failure.getMessage());
            } catch (IOException failure) {
                return failed(generating, safeName, "OUTPUT_NOT_WRITABLE", failure.getMessage());
            } catch (RuntimeException failure) {
                return failed(generating, safeName, "EXPORT_FAILED", "成品生成失败，请检查原件和模板");
            }
            var prepared = prepare(generating, files);
            checkpoint.reached(Boundary.MANIFEST_COMMITTED, prepared.record());
            var result = publisher.publish(prepared.record(), prepared.staging(),
                    () -> checkpoint.reached(Boundary.MOVED, prepared.record()));
            return finalize(prepared, result, safeName);
        }
    }

    public Record query(String exportId) {
        var value = database.read(connection -> byId(connection, exportId));
        return value == null ? null : value.record();
    }

    public List<Record> history(String caseId) {
        return database.read(connection -> list(connection, "WHERE case_id = ? ORDER BY created_at DESC, rowid DESC", caseId)
                .stream().map(Attempt::record).toList());
    }

    public Path preferredRoot(String caseId) {
        return database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT output_root FROM reimbursement_case WHERE id = ?")) {
                statement.setString(1, caseId);
                try (var rows = statement.executeQuery()) {
                    if (rows.next() && rows.getString(1) != null) {
                        return Path.of(rows.getString(1));
                    }
                }
            }
            return appPaths.defaultOutputRoot();
        });
    }

    public Path verifiedFile(String exportId, FileKind kind) {
        Record record = query(exportId);
        if (record == null) {
            throw new CaseException("EXPORT_NOT_READY", "找不到成品记录");
        }
        return ExportPublisher.verifiedFile(record, kind);
    }

    /** Run at startup with the OS instance lock already held, before accepting new jobs. */
    public List<Record> recover() {
        var active = database.read(connection -> list(connection, "WHERE state IN " + ACTIVE + " ORDER BY rowid", null));
        var results = new ArrayList<Record>();
        for (var attempt : active) {
            results.add(recover(attempt));
        }
        try {
            new OcrWorkspace(appPaths.cacheDirectory()).cleanupOrphans();
        } catch (IOException failure) {
            System.getLogger(ExportService.class.getName()).log(System.Logger.Level.WARNING, "OCR_ORPHAN_CLEANUP_FAILED");
        }
        return List.copyOf(results);
    }

    public Record recoverReady(String exportId) {
        var found = database.read(connection -> byId(connection, exportId));
        if (found == null || found.record().state() != State.READY_TO_PUBLISH) {
            return found == null ? null : found.record();
        }
        try (var job = jobs.begin(found.record().caseId())) {
            var latest = database.read(connection -> byId(connection, exportId));
            return latest.record().state() == State.READY_TO_PUBLISH ? recover(latest) : latest.record();
        }
    }

    private Record recover(Attempt attempt) {
        return finalize(attempt, publisher.recover(attempt.record(), attempt.staging()),
                safeNameFromDirectory(attempt.record()));
    }

    private String loadReimburser() {
        try {
            return settings.load();
        } catch (ReimburserSettings.SettingsException failure) {
            throw new CaseException(failure.code(), failure.getMessage(), failure);
        }
    }

    private void preflight(ExportSnapshot snapshot) {
        if (snapshot.type() == null) {
            throw new CaseException("TYPE_REQUIRED", "请选择报销类型");
        }
        if (!snapshot.unavailable().isEmpty()) {
            var source = snapshot.unavailable().getFirst();
            throw new CaseException(source.code(), "请先核对 " + source.relativePath()
                    + (source.sourcePage() > 0 ? " 第 " + source.sourcePage() + " 页" : ""));
        }
        if (snapshot.pages().isEmpty()) {
            throw new CaseException("NO_PRINTABLE_PAGES", "此报销没有可打印凭证页");
        }
        templates.resolveByHash(snapshot.templateHash(), snapshot.type());
    }

    private Record replay(Attempt attempt, long revision, Path requested) {
        Path root = requested == null ? attempt.record().outputRoot() : paths.normalizeOutputRequest(requested);
        if (!fingerprint(revision, root).equals(attempt.fingerprint())) {
            throw new CaseException("REQUEST_ID_MISMATCH", "同一生成请求号对应的版本或输出目录不同",
                    attempt.record().exportId(), null);
        }
        return attempt.record();
    }

    private Attempt findRequestOrRejectActive(String caseId, String requestId) {
        return database.read(connection -> {
            var attempt = byRequest(connection, caseId, requestId);
            if (attempt == null) {
                CaseService.requireNoActivePublication(connection, caseId);
            }
            return attempt;
        });
    }

    private static void verifyRetry(Connection connection, String caseId, String retryOf) throws SQLException {
        if (retryOf == null) {
            return;
        }
        Attempt previous = byId(connection, retryOf);
        if (previous == null || !caseId.equals(previous.record().caseId())
                || (previous.record().state() != State.FAILED && previous.record().state() != State.CONFLICT)) {
            throw new CaseException("INVALID_RETRY", "该记录不可作为失败重试来源 " + retryOf);
        }
    }

    private static Attempt insert(Connection connection, ExportSnapshot snapshot, Path root, String requestId,
                                  String safeName, String retryOf) throws Exception {
        String exportId = UUID.randomUUID().toString();
        Path parent = root.resolve(safeName + "-" + snapshot.caseId());
        Path destination = parent.resolve(exportId);
        Path staging = parent.resolve(ExportPublisher.STAGE_PREFIX + exportId);
        String fingerprint = fingerprint(snapshot.revision(), root);
        try (var statement = connection.prepareStatement("""
                INSERT INTO export_record (export_id, case_id, revision, request_id, request_fingerprint, retry_of,
                                           rule_version, template_hash, snapshot_json, state, staging_path, final_path)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'GENERATING', ?, ?)
                """)) {
            statement.setString(1, exportId);
            statement.setString(2, snapshot.caseId());
            statement.setLong(3, snapshot.revision());
            statement.setString(4, requestId);
            statement.setString(5, fingerprint);
            statement.setString(6, retryOf);
            statement.setString(7, snapshot.ruleVersion());
            statement.setString(8, snapshot.templateHash());
            statement.setString(9, JSON.writeValueAsString(snapshot));
            statement.setString(10, staging.toString());
            statement.setString(11, destination.toString());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("UPDATE reimbursement_case SET output_root = ? WHERE id = ?")) {
            statement.setString(1, root.toString());
            statement.setString(2, snapshot.caseId());
            statement.executeUpdate();
        }
        return new Attempt(new Record(exportId, snapshot.caseId(), snapshot.revision(), requestId, retryOf,
                State.GENERATING, root, destination, null, null, List.of()), staging, fingerprint);
    }

    private Attempt prepare(Attempt generating, List<PublishedFile> files) {
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO export_file (export_id, kind, file_name, sha256, size) VALUES (?, ?, ?, ?, ?)
                    """)) {
                for (var file : files) {
                    statement.setString(1, generating.record().exportId());
                    statement.setString(2, file.kind().name());
                    statement.setString(3, file.path().getFileName().toString());
                    statement.setString(4, file.sha256());
                    statement.setLong(5, file.size());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            changeState(connection, generating.record(), State.READY_TO_PUBLISH, null, null);
            return byId(connection, generating.record().exportId());
        });
    }

    private Record failed(Attempt attempt, String safeName, String code, String message) {
        return finalize(attempt, new ExportPublisher.Outcome(State.FAILED, code, message), safeName);
    }

    private Record finalize(Attempt attempt, ExportPublisher.Outcome outcome, String safeName) {
        var updated = database.transaction(connection -> {
            var previous = attempt.record();
            changeState(connection, previous, outcome.state(), outcome.code(), outcome.message());
            if (outcome.state() == State.SUCCEEDED) {
                try (var statement = connection.prepareStatement("UPDATE reimbursement_case SET current_export_id = ? WHERE id = ?")) {
                    statement.setString(1, previous.exportId());
                    statement.setString(2, previous.caseId());
                    statement.executeUpdate();
                }
            }
            return byId(connection, previous.exportId()).record();
        });
        if (outcome.state() == State.FAILED) {
            publisher.cleanupFailed(updated, attempt.staging(), safeName);
        }
        return updated;
    }

    private static void changeState(Connection connection, Record previous, State next, String code, String message)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE export_record SET state = ?, error = ? WHERE export_id = ? AND state = ?
                """)) {
            statement.setString(1, next.name());
            statement.setString(2, code == null ? null : code + "\n" + (message == null ? code : message));
            statement.setString(3, previous.exportId());
            statement.setString(4, previous.state().name());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("成品状态发生并发变化");
            }
        }
    }

    private static Attempt byId(Connection connection, String exportId) throws SQLException {
        try (var statement = connection.prepareStatement(COLUMNS + "WHERE export_id = ?")) {
            statement.setString(1, exportId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(connection, rows) : null;
            }
        }
    }

    private static Attempt byRequest(Connection connection, String caseId, String requestId) throws SQLException {
        try (var statement = connection.prepareStatement(COLUMNS + "WHERE case_id = ? AND request_id = ?")) {
            statement.setString(1, caseId);
            statement.setString(2, requestId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? read(connection, rows) : null;
            }
        }
    }

    private static List<Attempt> list(Connection connection, String suffix, String caseId) throws SQLException {
        try (var statement = connection.prepareStatement(COLUMNS + suffix)) {
            if (caseId != null) {
                statement.setString(1, caseId);
            }
            try (var rows = statement.executeQuery()) {
                var entries = new ArrayList<Attempt>();
                while (rows.next()) {
                    entries.add(read(connection, rows));
                }
                return List.copyOf(entries);
            }
        }
    }

    private static Attempt read(Connection connection, ResultSet rows) throws SQLException {
        String exportId = rows.getString("export_id");
        Path finalDirectory = Path.of(rows.getString("final_path"));
        List<PublishedFile> files = files(connection, exportId, finalDirectory);
        String error = rows.getString("error");
        String[] status = error == null ? new String[0] : error.split("\n", 2);
        var record = new Record(exportId, rows.getString("case_id"), rows.getLong("revision"),
                rows.getString("request_id"), rows.getString("retry_of"), State.valueOf(rows.getString("state")),
                finalDirectory.getParent().getParent(), finalDirectory, status.length >= 1 ? status[0] : null,
                status.length >= 2 ? status[1] : null, files);
        return new Attempt(record, Path.of(rows.getString("staging_path")), rows.getString("request_fingerprint"));
    }

    private static List<PublishedFile> files(Connection connection, String exportId, Path finalDirectory) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT kind, file_name, sha256, size FROM export_file WHERE export_id = ? ORDER BY kind DESC
                """)) {
            statement.setString(1, exportId);
            try (var rows = statement.executeQuery()) {
                var files = new ArrayList<PublishedFile>();
                while (rows.next()) {
                    Path name = Path.of(rows.getString("file_name"));
                    if (name.isAbsolute() || name.getNameCount() != 1 || name.toString().equals("..") || name.toString().equals(".")) {
                        throw new SQLException("成品登记文件名无效");
                    }
                    files.add(new PublishedFile(FileKind.valueOf(rows.getString("kind")),
                            finalDirectory.resolve(name), rows.getString("sha256"), rows.getLong("size")));
                }
                return files;
            }
        }
    }

    private static String fingerprint(long revision, Path root) {
        return revision + "\n" + root;
    }

    static String safeName(String name) {
        var normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
                .replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip().replaceAll("^[. ]+|[. ]+$", "");
        if (normalized.isEmpty()) {
            return "报销";
        }
        if (WINDOWS_RESERVED.matcher(normalized).matches()) {
            normalized = "_" + normalized;
        }
        var result = new StringBuilder();
        int bytes = 0;
        for (int codePoint : normalized.codePoints().toArray()) {
            String value = Character.toString(codePoint);
            bytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 72 || result.length() + value.length() > 40) {
                break;
            }
            result.append(value);
        }
        return result.toString().replaceAll("[. ]+$", "");
    }

    private static String safeNameFromDirectory(Record record) {
        String name = record.finalDirectory().getParent().getFileName().toString();
        return name.substring(0, name.length() - record.caseId().length() - 1);
    }
}
