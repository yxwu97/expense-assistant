package expenseassistant.casework;

import expenseassistant.intake.PathPolicy;
import expenseassistant.intake.TypeInference;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.output.TemplateArchive;
import expenseassistant.store.Database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class CaseService {
    public static final String RULE_VERSION = "3";
    private static final String PRIVATE_CAR_RULE_VERSION = "2";
    private static final String SELECT_COLUMNS = """
            SELECT id, batch_real_path, case_real_path, type, revision, template_hash,
                   rule_version, scan_generation, current_export_id FROM reimbursement_case
            """;
    private final Database database;
    private final PathPolicy pathPolicy;

    public CaseService(Database database, PathPolicy pathPolicy) {
        this.database = database;
        this.pathPolicy = pathPolicy;
    }

    public record CaseInfo(String id, Path batchRoot, Path folder, CaseType type, long revision,
                           String templateHash, String ruleVersion, long scanGeneration, String currentExportId) {
        public boolean usesLegacyMileageRule() {
            return type == CaseType.PRIVATE_CAR
                    && !PRIVATE_CAR_RULE_VERSION.equals(ruleVersion) && !RULE_VERSION.equals(ruleVersion);
        }
    }

    public static String ruleVersionFor(CaseType type) {
        return RULE_VERSION;
    }

    public CaseInfo openCase(Path caseFolder) {
        Path folder = caseFolder.toAbsolutePath().normalize();
        Path parent = folder.getParent();
        if (parent == null) {
            throw new CaseException("INVALID_CASE_FOLDER", "请选择包含报销凭证的目录。 " + folder);
        }
        return openCase(parent, folder);
    }

    public CaseInfo openCase(Path batchRoot, Path caseFolder) {
        var location = pathPolicy.validateCase(batchRoot, caseFolder);
        CaseType inferred = TypeInference.infer(location.folder().getFileName().toString());
        String batch = location.batchRoot().toString();
        String folder = location.folder().toString();
        return database.transaction(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO reimbursement_case(id, batch_real_path, case_real_path, batch_path_key,
                            case_path_key, type, type_origin, template_hash, rule_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(batch_path_key, case_path_key) DO NOTHING
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, batch);
                insert.setString(3, folder);
                insert.setString(4, batch);
                insert.setString(5, folder);
                insert.setString(6, inferred == null ? null : inferred.name());
                insert.setString(7, inferred == null ? "UNKNOWN" : "INFERRED");
                insert.setString(8, TemplateArchive.BUNDLED_SHA256);
                insert.setString(9, ruleVersionFor(inferred));
                insert.executeUpdate();
            }
            try (var query = connection.prepareStatement(SELECT_COLUMNS + "WHERE batch_path_key = ? AND case_path_key = ?")) {
                query.setString(1, batch);
                query.setString(2, folder);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        return read(rows);
                    }
                    throw new SQLException("刚打开的报销草稿缺失");
                }
            }
        });
    }

    public CaseInfo load(String caseId) {
        return database.read(connection -> load(connection, caseId));
    }

    public CaseInfo loadForMutation(String caseId, long expectedRevision) {
        return database.read(connection -> {
            var current = load(connection, caseId);
            requireRevision(current, expectedRevision);
            requireNoActivePublication(connection, caseId);
            return current;
        });
    }

    public static CaseInfo load(Connection connection, String caseId) throws SQLException {
        try (var query = connection.prepareStatement(SELECT_COLUMNS + "WHERE id = ?")) {
            query.setString(1, caseId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    return read(rows);
                }
            }
        }
        throw new CaseException("INVALID_CASE_FOLDER", "找不到报销草稿 " + caseId);
    }

    public static void requireRevision(CaseInfo info, long expectedRevision) {
        if (info.revision() != expectedRevision) {
            throw new CaseException("STALE_REVISION", "草稿已更新到版本 " + info.revision() + "，请重新载入核对。");
        }
    }

    public static void requireNoActivePublication(Connection connection, String caseId) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT export_id FROM export_record WHERE case_id = ?
                    AND state IN ('GENERATING', 'READY_TO_PUBLISH')
                """)) {
            query.setString(1, caseId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    throw new CaseException("BUSY", "这笔报销有待完成的生成任务。", rows.getString(1), null);
                }
            }
        }
    }

    private static CaseInfo read(ResultSet row) throws SQLException {
        String type = row.getString("type");
        return new CaseInfo(row.getString("id"),
                Path.of(row.getString("batch_real_path")), Path.of(row.getString("case_real_path")),
                type == null ? null : CaseType.valueOf(type), row.getLong("revision"),
                row.getString("template_hash"), row.getString("rule_version"),
                row.getLong("scan_generation"), row.getString("current_export_id"));
    }
}
