package expenseassistant.draft;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import expenseassistant.draft.CaseView.AttachmentSetting;
import expenseassistant.draft.CaseView.OverrideView;
import expenseassistant.draft.CaseView.Source;
import expenseassistant.intake.ScanService.SourceFile;
import expenseassistant.matching.ExpenseAmounts.Amount;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher;
import expenseassistant.matching.ExpenseMatcher.Claim;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.matching.ExpenseMatcher.Link;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.output.ExportSnapshot.Layout;
import expenseassistant.output.ExportSnapshot.UnavailableSource;
import expenseassistant.recognition.PageResult;
import expenseassistant.recognition.PageResult.Bounds;
import expenseassistant.recognition.PageResult.FieldCandidate;
import expenseassistant.recognition.PageResult.SummaryReference;
import expenseassistant.recognition.PageResult.TripSegment;

final class DraftStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final Connection conn;
    private final String caseId;

    DraftStore(Connection conn, String caseId) {
        this.conn = conn;
        this.caseId = caseId;
    }

    List<Source> sources() throws SQLException {
        List<Source> documents = new ArrayList<>();
        for (var doc : select("SELECT doc_id, sha256 FROM source_document WHERE case_id = ?",
                row -> new SourceKey(row.getString(1), row.getString(2)), caseId)) {
            List<Binding> paths = select("""
                    SELECT path_id,doc_id,relative_path,path_key,present,path_error FROM source_path
                    WHERE case_id=? AND doc_id=? ORDER BY path_id DESC
                    """, DraftStore::binding, caseId, doc.id());
            if (paths.isEmpty()) {
                continue;
            }
            var present = paths.stream().filter(Binding::present)
                    .sorted(Comparator.comparing(Binding::relative, ExpenseMatcher.NATURAL_PATH_ORDER)).toList();
            var primary = present.stream().filter(path -> path.error() == null).findFirst()
                    .orElseGet(() -> present.isEmpty() ? paths.getFirst() : present.getFirst());
            var alternatives = present.stream().map(Binding::relative).toList();
            var pages = pages(doc.id());
            documents.add(new Source(doc.id(), doc.hash(), primary.relative(), alternatives, present.isEmpty(),
                    primary.error(), pages, ExpenseMatcher.groupPages(doc.id(), doc.hash(), primary.relative(), pages)));
        }
        return documents.stream().sorted(Comparator.comparing(Source::relativePath, ExpenseMatcher.NATURAL_PATH_ORDER)
                .thenComparing(Source::docId)).toList();
    }

    private List<PageResult> pages(String docId) throws SQLException {
        List<PageResult> pages = new ArrayList<>();
        for (var page : select("""
                SELECT page_no,kind,render_state,warnings FROM document_page WHERE doc_id=? ORDER BY page_no
                """, row -> new PageRow(row.getInt(1), PageResult.Kind.valueOf(row.getString(2)),
                "AVAILABLE".equals(row.getString(3)), pageMetadata(row.getString(4))), docId)) {
            var fields = select("""
                    SELECT key,raw,normalized,method,bbox,validation FROM field_candidate
                    WHERE doc_id=? AND page_no=? ORDER BY rowid
                    """, row -> new FieldCandidate(row.getString(1), row.getString(2), row.getString(3),
                    PageResult.Method.valueOf(row.getString(4)), decode(row.getString(5), Bounds.class),
                    row.getString(6)), docId, page.number());
            var segments = select("""
                    SELECT d.start_date,d.end_date,d.origin,d.destination,d.amount_minor,d.distance_km
                    FROM trip_segment d JOIN evidence_unit e ON d.unit_id=e.unit_id
                    WHERE e.doc_id=? AND e.first_page=? ORDER BY d.sequence_no
                    """, row -> new TripSegment(date(row.getString(1)), date(row.getString(2)), row.getString(3),
                    row.getString(4), nullableLong(row, 5), row.getString(6)), docId, page.number());
            var refs = select("""
                    SELECT d.invoice_key,d.face_minor,d.split_minor
                    FROM summary_reference d JOIN evidence_unit e ON d.unit_id=e.unit_id
                    WHERE e.doc_id=? AND e.first_page=? ORDER BY d.sequence_no
                    """, row -> new SummaryReference(row.getString(1), nullableLong(row, 2), nullableLong(row, 3)),
                    docId, page.number());
            pages.add(new PageResult(page.number(), page.kind(), page.renderable(), fields, segments, refs,
                    page.metadata().warnings(), page.metadata().version()));
        }
        return List.copyOf(pages);
    }

    String putSource(SourceFile file, List<PageResult> pages) throws SQLException {
        String id = ExpenseMatcher.stableId(caseId, file.sha256());
        update("""
                INSERT INTO source_document(doc_id,case_id,sha256,format,page_count,parse_state)
                VALUES(?,?,?,?,?,?) ON CONFLICT(case_id,sha256) DO UPDATE
                SET page_count=excluded.page_count, parse_state=excluded.parse_state
                """, id, caseId, file.sha256(), file.format().name(), pages.size(),
                pages.stream().allMatch(PageResult::renderable)
                        ? pages.stream().anyMatch(page -> !page.warnings().isEmpty()) ? "PARTIAL" : "COMPLETE"
                        : pages.stream().anyMatch(PageResult::renderable) ? "PARTIAL" : "FAILED");
        if (pages.equals(pages(id))) {
            return id;
        }
        update("DELETE FROM evidence_unit WHERE doc_id=?", id);
        update("DELETE FROM document_page WHERE doc_id=?", id);
        for (PageResult page : pages) {
            update("""
                    INSERT INTO document_page(case_id,doc_id,page_no,kind,render_state,warnings)
                    VALUES(?,?,?,?,?,?)
                    """, caseId, id, page.pageNo(), page.kind().name(), page.renderable() ? "AVAILABLE" : "BROKEN",
                    encode(new PageMetadata(page.warnings(), page.parserVersion())));
            for (int index = 0; index < page.fields().size(); index++) {
                var field = page.fields().get(index);
                update("""
                        INSERT INTO field_candidate(candidate_id,case_id,doc_id,page_no,key,raw,normalized,method,
                                bbox,validation,parser_version) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                        """, ExpenseMatcher.stableId(id, page.pageNo() + "/" + index), caseId, id, page.pageNo(),
                        field.key(), field.raw(), field.normalized(), field.method().name(),
                        field.bounds() == null ? null : encode(field.bounds()), field.validation(), page.parserVersion());
            }
        }
        for (var unit : ExpenseMatcher.groupPages(id, file.sha256(), file.relativePath(), pages)) {
            update("""
                    INSERT INTO evidence_unit(unit_id,case_id,doc_id,first_page,last_page,kind,invoice_key,
                            order_key,summary_key,face_minor,currency) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, unit.unitId(), caseId, id, unit.page().pageNo(), unit.pages().getLast().pageNo(),
                    unit.page().kind().name(), unit.invoiceKey(), unit.orderKey(), unit.page().value("summaryKey"),
                    unit.amount(), unit.page().value("currency"));
            int seq = 0;
            for (var page : unit.pages()) {
                for (var segment : page.segments()) {
                    update("""
                            INSERT INTO trip_segment(case_id,unit_id,sequence_no,origin,destination,start_date,end_date,
                                    amount_minor,distance_km) VALUES(?,?,?,?,?,?,?,?,?)
                            """, caseId, unit.unitId(), ++seq, segment.origin(), segment.destination(),
                            segment.start(), segment.end(), segment.minor(), segment.distanceKm());
                }
            }
            seq = 0;
            for (var page : unit.pages()) {
                for (var ref : page.summaryReferences()) {
                    update("""
                            INSERT INTO summary_reference(case_id,unit_id,sequence_no,invoice_key,face_minor,split_minor)
                            VALUES(?,?,?,?,?,?)
                            """, caseId, unit.unitId(), ++seq, ref.invoiceKey(), ref.faceMinor(), ref.splitMinor());
                }
            }
        }
        return id;
    }

    List<Binding> activeBindings() throws SQLException {
        return select("SELECT path_id,doc_id,relative_path,path_key,present,path_error FROM source_path WHERE case_id=? AND present=1",
                DraftStore::binding, caseId);
    }

    private static Binding binding(ResultSet row) throws SQLException {
        return new Binding(row.getLong(1), row.getString(2), row.getString(3), row.getString(4),
                row.getBoolean(5), row.getString(6));
    }

    void bind(String docId, String relative, String key, String error, long generation) throws SQLException {
        List<Binding> existing = select("""
                SELECT path_id,doc_id,relative_path,path_key,present,path_error FROM source_path
                WHERE case_id=? AND path_key=? AND present=1
                """, DraftStore::binding, caseId, key);
        if (!existing.isEmpty()) {
            var entry = existing.getFirst();
            if (Objects.equals(entry.docId(), docId)) {
                update("UPDATE source_path SET relative_path=?,path_error=?,scan_generation=? WHERE path_id=?",
                        relative, error, generation, entry.id());
                return;
            }
            deactivate(entry, generation);
        }
        update("""
                INSERT INTO source_path(case_id,doc_id,relative_path,path_key,present,scan_generation,path_error)
                VALUES(?,?,?,?,1,?,?)
                """, caseId, docId, relative, key, generation, error);
    }

    void deactivate(Binding binding, long generation) throws SQLException {
        update("UPDATE source_path SET present=0, scan_generation=? WHERE path_id=?", generation, binding.id());
    }

    List<UnavailableSource> pathErrors() throws SQLException {
        return select("""
                SELECT doc_id,relative_path,path_error FROM source_path
                WHERE case_id=? AND present=1 AND path_error IS NOT NULL ORDER BY path_key
                """, row -> new UnavailableSource(row.getString(1), row.getString(2), 0, row.getString(3)), caseId);
    }

    List<Item> items() throws SQLException {
        List<Item> items = new ArrayList<>();
        for (var item : select("""
                SELECT id,subtype,start_date,end_date,description,stable_order,review_reasons FROM expense_item
                WHERE case_id=? ORDER BY start_date IS NULL,start_date,stable_order
                """, row -> new ItemBasis(row.getString(1), row.getString(2), date(row.getString(3)),
                date(row.getString(4)), row.getString(5), row.getLong(6), strings(row.getString(7))), caseId)) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (var field : select("SELECT key,typed_value FROM item_field WHERE item_id=? AND typed_value IS NOT NULL ORDER BY key",
                    row -> Map.entry(row.getString(1), row.getString(2)), item.id())) {
                fields.put(field.getKey(), field.getValue());
            }
            Map<Component, Amount> amounts = new LinkedHashMap<>();
            for (var amount : select("""
                    SELECT component,face_value,original_currency,confirmed_cny_minor,basis FROM expense_amount
                    WHERE item_id=? ORDER BY component
                    """, row -> Map.entry(Component.valueOf(row.getString(1)), new Amount(row.getString(2),
                    row.getString(3), nullableLong(row, 4), row.getString(5))), item.id())) {
                amounts.put(amount.getKey(), amount.getValue());
            }
            items.add(new Item(item.id(), item.subtype(), item.start(), item.end(), item.description(), item.order(),
                    fields, amounts, item.reasons()));
        }
        return List.copyOf(items);
    }

    List<Link> links() throws SQLException {
        return select("""
                SELECT item_id,unit_id,role,basis FROM expense_evidence WHERE case_id=? ORDER BY item_id,unit_id,role
                """, row -> new Link(row.getString(1), row.getString(2), Role.valueOf(row.getString(3)), row.getString(4)), caseId);
    }

    List<Claim> claims() throws SQLException {
        return select("""
                SELECT charge_key,owner_item_id,component,cny_minor FROM charge_claim WHERE case_id=? ORDER BY charge_key
                """, row -> new Claim(row.getString(1), row.getString(2), Component.valueOf(row.getString(3)),
                nullableLong(row, 4)), caseId);
    }

    void replaceItems(List<Item> items, List<Link> links, List<Claim> claims) throws SQLException {
        update("DELETE FROM expense_item WHERE case_id=?", caseId);
        for (Item item : items) {
            update("""
                    INSERT INTO expense_item(id,case_id,subtype,start_date,end_date,description,stable_order,review_reasons)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, item.id(), caseId, item.subtype(), item.startDate(), item.endDate(), item.description(),
                    item.stableOrder(), encode(item.reviewReasons()));
            for (var entry : item.fields().entrySet()) {
                update("INSERT INTO item_field(case_id,item_id,key,typed_value,state) VALUES(?,?,?,?,?)",
                        caseId, item.id(), entry.getKey(), entry.getValue(), "AVAILABLE");
            }
            for (var entry : item.amounts().entrySet()) {
                Amount amount = entry.getValue();
                update("""
                        INSERT INTO expense_amount(case_id,item_id,component,face_value,original_currency,confirmed_cny_minor,basis)
                        VALUES(?,?,?,?,?,?,?)
                        """, caseId, item.id(), entry.getKey().name(), amount.faceValue(), amount.originalCurrency(),
                        amount.confirmedMinor(), amount.basis());
            }
        }
        for (Link link : links) {
            update("""
                    INSERT INTO expense_evidence(case_id,item_id,unit_id,role,basis,status) VALUES(?,?,?,?,?,?)
                    """, caseId, link.itemId(), link.unitId(), link.role().name(), link.basis(),
                    link.role() == Role.PENDING ? "REVIEW" : "ACTIVE");
        }
        for (Claim claim : claims) {
            update("""
                    INSERT INTO charge_claim(case_id,charge_key,owner_item_id,component,cny_minor) VALUES(?,?,?,?,?)
                    """, caseId, claim.chargeKey(), claim.itemId(), claim.component().name(), claim.minor());
        }
    }

    List<OverrideView> overrides() throws SQLException {
        return select("""
                SELECT target_id,field_key,action,value,source_hashes,updated_revision FROM field_override
                WHERE case_id=? ORDER BY target_id,field_key
                """, row -> new OverrideView(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), strings(row.getString(5)), row.getLong(6)), caseId);
    }

    void putOverride(OverrideView value) throws SQLException {
        update("""
                INSERT INTO field_override(case_id,target_id,field_key,action,value,source_hashes,updated_revision)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(case_id,target_id,field_key) DO UPDATE SET
                action=excluded.action, value=excluded.value, source_hashes=excluded.source_hashes,
                updated_revision=excluded.updated_revision
                """, caseId, value.targetId(), value.fieldKey(), value.action(), value.value(),
                encode(value.sourceHashes()), value.updatedRevision());
    }

    void removeOverride(String itemId, String fieldKey) throws SQLException {
        update("DELETE FROM field_override WHERE case_id=? AND target_id=? AND field_key=?", caseId, itemId, fieldKey);
    }

    List<AttachmentSetting> settings() throws SQLException {
        return select("""
                SELECT doc_id,first_page,last_page,caption,layout,order_key,exclusion_reason
                FROM attachment_setting WHERE case_id=? ORDER BY doc_id,first_page,last_page
                """, row -> new AttachmentSetting(row.getString(1), row.getInt(2), row.getInt(3), row.getString(4),
                row.getString(5) == null ? null : Layout.valueOf(row.getString(5)), nullableLong(row, 6),
                row.getString(7)), caseId);
    }

    void putSetting(AttachmentSetting setting) throws SQLException {
        update("""
                INSERT INTO attachment_setting(case_id,doc_id,first_page,last_page,caption,layout,order_key,exclusion_reason)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(doc_id,first_page,last_page) DO UPDATE SET caption=excluded.caption,
                    layout=excluded.layout,order_key=excluded.order_key,exclusion_reason=excluded.exclusion_reason
                """, caseId, setting.docId(), setting.firstPage(), setting.lastPage(), setting.caption(),
                setting.layout() == null ? null : setting.layout().name(), setting.orderKey(), setting.exclusionReason());
    }

    void update(String sql, Object... parameters) throws SQLException {
        try (var statement = bind(sql, parameters)) {
            statement.executeUpdate();
        }
    }

    private <T> List<T> select(String sql, RowMapper<T> mapper, Object... parameters) throws SQLException {
        List<T> result = new ArrayList<>();
        try (var statement = bind(sql, parameters); var rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(mapper.read(rows));
            }
        }
        return List.copyOf(result);
    }

    private PreparedStatement bind(String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = conn.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.length; i++) {
                Object argument = parameters[i];
                statement.setObject(i + 1, argument instanceof LocalDate ? argument.toString() : argument);
            }
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    private static Long nullableLong(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法保存草稿数据", failure);
        }
    }

    static <T> T decode(String value, Class<T> kind) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.readValue(value, kind);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法读取草稿数据", failure);
        }
    }

    private static List<String> strings(String value) {
        try {
            return JSON.readValue(value, STRINGS);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法读取草稿列表", failure);
        }
    }

    private static PageMetadata pageMetadata(String value) {
        return value.startsWith("[") ? new PageMetadata(strings(value), "1") : decode(value, PageMetadata.class);
    }

    record Binding(long id, String docId, String relative, String key, boolean present, String error) {}
    record PageMetadata(List<String> warnings, String version) {}
    private record SourceKey(String id, String hash) {}
    private record PageRow(int number, PageResult.Kind kind, boolean renderable, PageMetadata metadata) {}
    private record ItemBasis(String id, String subtype, LocalDate start, LocalDate end, String description,
                             long order, List<String> reasons) {}
    private interface RowMapper<T> { T read(ResultSet row) throws SQLException; }
}
