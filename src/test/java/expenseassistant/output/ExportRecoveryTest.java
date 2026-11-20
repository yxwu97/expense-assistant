package expenseassistant.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.draft.CaseView;
import expenseassistant.draft.DraftService;
import expenseassistant.intake.PathPolicy;
import expenseassistant.intake.ScanService;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.output.ExportService.Boundary;
import expenseassistant.output.ExportService.FileKind;
import expenseassistant.output.ExportService.Record;
import expenseassistant.output.ExportService.State;
import expenseassistant.platform.AppPaths;
import expenseassistant.recognition.RecognitionService;
import expenseassistant.store.Database;
import expenseassistant.store.InstanceLock;
import expenseassistant.store.StoreException;
import expenseassistant.support.ReceiptFixtures;
import expenseassistant.support.SampleCorpus;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExportRecoveryTest {
    @TempDir Path testDirectory;

    @ParameterizedTest
    @EnumSource(Boundary.class)
    void restartAfterKillingASubprocessAtEachPublicationBoundary(Boundary boundary) throws Exception {
        var fixture = fixture();
        Record earlier = fixture.generate("earlier");
        assertEquals(State.SUCCEEDED, earlier.state(), earlier.message());
        Path marked = Files.createDirectory(fixture.paths().cacheDirectory().resolve("ocr").resolve("page-owned"));
        Files.writeString(marked.resolve(".expense-assistant-ocr"), "expense-assistant/ocr/1\n");
        Files.writeString(marked.resolve("old-page.png"), "synthetic");
        Path unrelated = Files.createDirectory(marked.getParent().resolve("page-unmarked"));
        Files.writeString(unrelated.resolve("keep.txt"), "keep");

        Process process = startProbe(fixture, boundary);
        try {
            awaitCheckpoint(fixture.directory(), process);
            assertEquals("INSTANCE_LOCKED", assertThrows(StoreException.class,
                    () -> InstanceLock.acquire(fixture.paths().dataDirectory())).code());
            Record interrupted = fixture.exports().history(fixture.caseId()).getFirst();
            assertEquals(boundary == Boundary.GENERATING ? State.GENERATING : State.READY_TO_PUBLISH,
                    interrupted.state());
            assertEquals(earlier.exportId(), fixture.cases().load(fixture.caseId()).currentExportId());
            assertEquals(boundary == Boundary.MOVED, Files.isDirectory(interrupted.finalDirectory()));
            assertEquals(boundary != Boundary.MOVED, Files.isDirectory(staging(interrupted)));
            if (boundary == Boundary.GENERATING) {
                assertTrue(Files.isDirectory(fixture.paths().cacheDirectory().resolve("exports").resolve(interrupted.exportId())));
            }
            assertEquals(interrupted,
                    fixture.exports().generate(fixture.caseId(), fixture.revision(), fixture.destination(), "interrupted"));
            var busy = assertThrows(CaseException.class,
                    () -> fixture.generate("another-while-busy"));
            assertEquals("BUSY", busy.code());
            assertEquals(interrupted.exportId(), busy.relatedId());
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "子进程未终止");

            var reopened = services(fixture.directory(), null, ExportPublisher::atomicMove, (point, record) -> {});
            List<Record> recovered;
            try (var lock = InstanceLock.acquire(reopened.paths().dataDirectory())) {
                recovered = reopened.exports().recover();
                assertTrue(reopened.exports().recover().isEmpty());
            }
            assertEquals(1, recovered.size());
            var result = recovered.getFirst();
            assertEquals(interrupted.exportId(), result.exportId());
            assertEquals(boundary == Boundary.GENERATING ? State.FAILED : State.SUCCEEDED, result.state(), result.message());
            assertEquals(boundary == Boundary.GENERATING ? earlier.exportId() : result.exportId(),
                    reopened.cases().load(fixture.caseId()).currentExportId());
            assertFalse(Files.exists(staging(interrupted)));
            assertFalse(Files.exists(fixture.paths().cacheDirectory().resolve("exports").resolve(interrupted.exportId())));
            assertFalse(Files.exists(marked));
            assertTrue(Files.exists(unrelated.resolve("keep.txt")));
            if (result.state() == State.SUCCEEDED) {
                verifyPair(reopened, result);
            }
            verifyPair(reopened, earlier);
            assertEquals(result, reopened.exports().generate(fixture.caseId(), fixture.revision(), fixture.destination(), "interrupted"));
            assertEquals("REQUEST_ID_MISMATCH", assertThrows(CaseException.class, () -> reopened.exports().generate(
                    fixture.caseId(), fixture.revision() + 1, fixture.destination(), "interrupted")).code());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void offlineRootStaysReadyAndCanRecoverWithoutOriginal() throws Exception {
        var fixture = fixture();
        var staged = interruptedAtManifest(fixture);
        assertEquals(State.READY_TO_PUBLISH, staged.state());
        Path detached = fixture.directory().resolve("卸载的目标卷");
        Files.move(staged.outputRoot(), detached);
        Files.delete(fixture.source());
        var pending = fixture.exports().recoverReady(staged.exportId());
        assertEquals(State.READY_TO_PUBLISH, pending.state(), pending.message());
        assertEquals("OUTPUT_NOT_WRITABLE", pending.errorCode());
        assertFalse(Files.exists(staged.outputRoot()));
        assertNull(fixture.cases().load(fixture.caseId()).currentExportId());
        assertEquals(pending,
                fixture.exports().generate(fixture.caseId(), fixture.revision(), fixture.destination(), "pending"));
        Files.move(detached, staged.outputRoot());
        var recovered = fixture.exports().recoverReady(staged.exportId());
        assertEquals(State.SUCCEEDED, recovered.state(), recovered.message());
        verifyPair(fixture.services(), recovered);
    }

    @Test
    void keepsConflictAndPriorProductsAndCanRetryWithFreshRequest() throws Exception {
        var fixture = fixture();
        var earlier = fixture.generate("initial");
        var staged = interruptedAtManifest(fixture);
        Path obstacle = Files.createDirectory(staged.finalDirectory()).resolve("现有现场.txt");
        Files.writeString(obstacle, "preserve");
        var conflict = fixture.exports().recoverReady(staged.exportId());
        assertEquals(State.CONFLICT, conflict.state());
        assertEquals("preserve", Files.readString(obstacle));
        assertTrue(Files.isDirectory(staging(staged)));
        assertEquals(earlier.exportId(), fixture.cases().load(fixture.caseId()).currentExportId());
        verifyPair(fixture.services(), earlier);

        var replacement = fixture.exports().generate(fixture.caseId(), fixture.revision(), fixture.destination(), "replacement", conflict.exportId());
        assertEquals(State.SUCCEEDED, replacement.state(), replacement.message());
        assertEquals(conflict.exportId(), replacement.retryOf());
        assertEquals(replacement.exportId(), fixture.cases().load(fixture.caseId()).currentExportId());
        assertEquals(List.of(replacement.exportId(), conflict.exportId(), earlier.exportId()),
                fixture.exports().history(fixture.caseId()).stream().map(Record::exportId).toList());
        assertEquals(conflict,
                fixture.exports().generate(fixture.caseId(), fixture.revision(), fixture.destination(), "pending"));
        assertEquals("REQUEST_ID_MISMATCH", assertThrows(CaseException.class, () -> fixture.exports().generate(
                fixture.caseId(), fixture.revision(), fixture.directory().resolve("不同输出"), "pending")).code());
        assertTrue(Files.isDirectory(staging(staged)));
        assertEquals("preserve", Files.readString(obstacle));
    }

    @Test
    void incompleteStageAndUnsupportedAtomicMoveDoNotReplaceEarlierSuccess() throws Exception {
        var fixture = fixture();
        var earlier = fixture.generate("initial");
        var staged = interruptedAtManifest(fixture);
        Files.delete(staging(staged).resolve(staged.file(FileKind.DOCX).path().getFileName()));
        var failed = fixture.exports().recoverReady(staged.exportId());
        assertEquals(State.FAILED, failed.state());
        assertEquals("STAGING_INVALID", failed.errorCode());
        assertFalse(Files.isDirectory(failed.finalDirectory()));
        assertFalse(Files.exists(staging(staged)));

        var noAtomic = services(fixture.directory(), null, (from, to) -> {
            assertEquals(from.getParent(), to.getParent());
            assertEquals(Files.getFileStore(from), Files.getFileStore(to.getParent()));
            throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test volume");
        }, (point, record) -> {});
        var denied = noAtomic.exports().generate(fixture.caseId(), fixture.revision(), fixture.destination(), "deny");
        assertEquals(State.FAILED, denied.state());
        assertEquals("OUTPUT_NOT_ATOMIC", denied.errorCode());
        assertFalse(Files.exists(staging(denied)));
        assertEquals(earlier.exportId(), fixture.cases().load(fixture.caseId()).currentExportId());
        verifyPair(fixture.services(), earlier);
    }

    @Test
    void checksStaleRevisionAndPreflightAndDoesNotPublishChangedSource() throws Exception {
        var fixture = fixture();
        var first = fixture.generate("first");
        assertEquals(State.SUCCEEDED, first.state(), first.message());
        assertEquals("STALE_REVISION", assertThrows(CaseException.class, () -> fixture.exports().generate(
                fixture.caseId(), fixture.revision() - 1, fixture.destination(), "stale")).code());
        assertEquals("OUTPUT_NOT_WRITABLE", assertThrows(CaseException.class, () -> fixture.exports().generate(
                fixture.caseId(), fixture.revision(), fixture.source().getParent(), "unwritable")).code());
        assertEquals(1, fixture.exports().history(fixture.caseId()).size());
        Files.writeString(fixture.source(), "changed", StandardOpenOption.APPEND);
        var changed = fixture.generate("changed-source");
        assertEquals(State.FAILED, changed.state(), changed.message());
        assertEquals("SOURCE_CHANGED", changed.errorCode());
        assertEquals(first.exportId(), fixture.cases().load(fixture.caseId()).currentExportId());
        verifyPair(fixture.services(), first);
        Files.writeString(first.file(FileKind.XLSX).path(), "external edit", StandardOpenOption.APPEND);
        assertEquals("OUTPUT_CHANGED", assertThrows(CaseException.class, () ->
                fixture.exports().verifiedFile(first.exportId(), FileKind.XLSX)).code());
        Files.delete(first.file(FileKind.DOCX).path());
        assertEquals("OUTPUT_MISSING", assertThrows(CaseException.class, () ->
                fixture.exports().verifiedFile(first.exportId(), FileKind.DOCX)).code());
        assertEquals(first, fixture.exports().query(first.exportId()));
    }

    @Test
    void freezesExclusionReasonsAndConfirmationBasisWithThePublishedRevision() throws Exception {
        var fixture = fixture();
        Path other = fixture.source().resolveSibling("不随附.png");
        var distinct = new BufferedImage(100, 90, BufferedImage.TYPE_INT_RGB);
        distinct.setRGB(0, 0, 0xff);
        assertTrue(ImageIO.write(distinct, "PNG", other.toFile()));
        Files.writeString(fixture.source().resolveSibling("损坏.png"), "not a PNG");
        CaseView scanned;
        try (var ticket = fixture.services().scans().begin(fixture.caseId(), fixture.revision())) {
            scanned = fixture.services().drafts().mergeScan(fixture.services().scans().scan(ticket));
        }
        var attached = scanned.sources().stream().filter(source -> source.relativePath().equals("测试凭证.png"))
                .findFirst().orElseThrow();
        var excluded = scanned.sources().stream().filter(source -> source.relativePath().equals("不随附.png"))
                .findFirst().orElseThrow();
        var edited = fixture.services().drafts().apply(fixture.caseId(), scanned.info().revision(), List.of(
                new DraftService.AddExpense(attached.units().getFirst().unitId(), "审计费用", LocalDate.of(2026, 11, 19), null),
                new DraftService.SetAttachment(excluded.docId(), 1, 1, null, null, null, "重复材料"),
                new DraftService.ExcludePath("损坏.png", "坏图已核对")));
        String itemId = edited.items().getFirst().id();
        var priced = fixture.services().drafts().apply(fixture.caseId(), edited.info().revision(),
                List.of(new DraftService.SetAmount(itemId, Component.OTHER, "CNY", 12345L, "人工批准金额")));
        var output = fixture.exports().generate(fixture.caseId(), priced.info().revision(), fixture.destination(), "audit");
        assertEquals(State.SUCCEEDED, output.state(), output.message());
        var database = Database.open(fixture.paths().databaseFile());
        String json = database.read(connection -> {
            try (var statement = connection.prepareStatement("SELECT snapshot_json FROM export_record WHERE export_id = ?")) {
                statement.setString(1, output.exportId());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getString(1);
                }
            }
        });
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var snapshot = mapper.readValue(json, ExportSnapshot.class);
        assertEquals(priced.info().revision(), snapshot.revision());
        assertEquals(List.of(attached.docId()), snapshot.pages().stream().map(ExportSnapshot.AttachmentPage::docId).toList());
        assertEquals(2, snapshot.audit().exclusions().size());
        assertTrue(snapshot.audit().exclusions().stream().anyMatch(exclusion ->
                excluded.docId().equals(exclusion.docId()) && "重复材料".equals(exclusion.reason())));
        assertTrue(snapshot.audit().exclusions().stream().anyMatch(exclusion ->
                "损坏.png".equals(exclusion.relativePath()) && "坏图已核对".equals(exclusion.reason())));
        assertTrue(snapshot.audit().amounts().stream().anyMatch(amount ->
                itemId.equals(amount.itemId()) && amount.confirmedMinor() == 12345L && "人工批准金额".equals(amount.basis())));
        assertTrue(snapshot.audit().evidence().stream().anyMatch(basis ->
                itemId.equals(basis.itemId()) && attached.sha256().equals(basis.sha256()) && basis.firstPage() == 1));
        ObjectNode previousFormat = (ObjectNode) mapper.readTree(json);
        previousFormat.remove("audit");
        assertTrue(mapper.treeToValue(previousFormat, ExportSnapshot.class).audit().exclusions().isEmpty());
    }

    @Test
    void cacheAndOutputMayBeOnDifferentVolumes() throws Exception {
        String mount = System.getProperty("expenseassistant.crossVolumeCacheRoot");
        assumeTrue(mount != null && Files.isDirectory(Path.of(mount)), "缺少第二个可写测试卷");
        var fixture = fixture();
        Path temporary = Files.createTempDirectory(Path.of(mount), "expense-export-test-");
        try {
            assumeTrue(!Files.getFileStore(temporary).equals(Files.getFileStore(fixture.directory())), "缓存与输出仍位于同一卷");
            var split = services(fixture.directory(), temporary,
                    (from, to) -> {
                        assertEquals(Files.getFileStore(from), Files.getFileStore(to.getParent()));
                        assertNotEquals(Files.getFileStore(from), Files.getFileStore(temporary));
                        ExportPublisher.atomicMove(from, to);
                    }, (point, record) -> {});
            var success = split.exports().generate(fixture.caseId(), fixture.revision(), fixture.destination(), "cross-volume");
            assertEquals(State.SUCCEEDED, success.state(), success.message());
            verifyPair(split, success);
        } finally {
            Files.deleteIfExists(temporary.resolve("exports"));
            Files.delete(temporary);
        }
    }

    @Test
    void legacyUsdPairReplaysWithTheSameDigestsAndNewRequestsRequireReview() throws Exception {
        Path directory = testDirectory.toRealPath();
        var service = services(directory, null, ExportPublisher::atomicMove, (point, record) -> {});
        Path folder = Files.createDirectories(directory.resolve("样本批次/其他报销"));
        ReceiptFixtures.receipt(folder.resolve("收据.pdf"), "Shop", "REC-001", "USD 100.00");
        var info = service.cases().openCase(folder);
        var output = service.paths().defaultOutputRoot();
        CaseView draft;
        try (var ticket = service.scans().begin(info.id(), info.revision())) {
            draft = service.drafts().mergeScan(service.scans().scan(ticket));
        }
        String id = info.id();
        long oldRevision = draft.info().revision();
        seedLegacyUsdAmounts(service.paths(), id);
        Record old = service.exports().generate(id, oldRevision, output, "old-usd");
        assertEquals(State.SUCCEEDED, old.state(), old.message());
        assertNull(otherAmount(old));

        Database.open(service.paths().databaseFile()).transaction(connection -> {
            update(connection, "UPDATE field_candidate SET normalized='USD' WHERE case_id=? AND key='currency'", id);
            return null;
        });
        assertEquals("AMOUNT_RULE_REFRESH_REQUIRED", assertThrows(CaseException.class, () ->
                service.exports().generate(id, oldRevision, output, "new-before-review")).code());
        assertEquals(1, service.exports().history(id).size());
        assertEquals(old, service.exports().generate(id, oldRevision, output, "old-usd"));
        verifyPair(service, old);

        var refreshed = service.drafts().loadForReview(id);
        assertEquals(oldRevision + 1, refreshed.info().revision());
        assertEquals(67000L, refreshed.confirmedTotal());
        assertEquals(refreshed, service.drafts().loadForReview(id));
        Record current = service.exports().generate(id, refreshed.info().revision(), output, "new-after-review");
        assertEquals(State.SUCCEEDED, current.state(), current.message());
        assertNotEquals(old.exportId(), current.exportId());
        assertEquals(670.00d, otherAmount(current));

        var reopened = services(directory, null, ExportPublisher::atomicMove, (point, record) -> {});
        reopened.exports().recover();
        assertEquals(current.exportId(), reopened.cases().load(id).currentExportId());
        assertEquals(old, reopened.exports().generate(id, oldRevision, output, "old-usd"));
        assertNull(otherAmount(old));
        verifyPair(reopened, old);
        verifyPair(reopened, current);
    }

    @Test
    void legacyTravelPairReplaysAndNewRequestsSplitTheSameRevisionAfterAnInterruption() throws Exception {
        Path directory = testDirectory.toRealPath();
        var service = services(directory, null, ExportPublisher::atomicMove, (point, record) -> {});
        var draft = scanTravelTickets(directory, service);
        String id = draft.info().id();
        long revision = draft.info().revision();
        Path output = service.paths().defaultOutputRoot();
        var old = seedLegacyTravelPair(service, draft, output);
        verifyPair(service, old);
        assertLegacyTravelSheet(old);

        var stopped = services(directory, null, ExportPublisher::atomicMove, (point, record) -> {
            if (point == Boundary.GENERATING) throw new SimulatedStop();
        });
        try (var lock = InstanceLock.acquire(stopped.paths().dataDirectory())) {
            assertThrows(SimulatedStop.class, () -> stopped.exports().generate(id, revision, output, "interrupted-travel"));
        }
        var reopened = services(directory, null, ExportPublisher::atomicMove, (point, record) -> {});
        try (var lock = InstanceLock.acquire(reopened.paths().dataDirectory())) {
            var recovered = reopened.exports().recover();
            assertEquals(1, recovered.size());
            var failed = recovered.getFirst();
            assertEquals(State.FAILED, failed.state());
            assertEquals("GENERATION_INTERRUPTED", failed.errorCode());
            assertEquals(failed, reopened.exports().generate(id, revision, output, "interrupted-travel"));
            assertTrue(reopened.exports().recover().isEmpty());
        }
        assertEquals(old.exportId(), reopened.cases().load(id).currentExportId());
        assertEquals(old, reopened.exports().generate(id, revision, output, "legacy-travel"));
        verifyPair(reopened, old);

        var current = reopened.exports().generate(id, revision, output, "split-travel");
        assertEquals(State.SUCCEEDED, current.state(), current.message());
        assertNotEquals(old.exportId(), current.exportId());
        assertEquals(revision, current.revision());
        assertEquals(current.exportId(), reopened.cases().load(id).currentExportId());
        assertSplitTravelSheet(current);
        verifyPair(reopened, current);
        assertEquals(old, reopened.exports().generate(id, revision, output, "legacy-travel"));
        verifyPair(reopened, old);
        assertLegacyTravelSheet(old);
    }

    private static CaseView scanTravelTickets(Path directory, Services service) throws IOException {
        Path original = SampleCorpus.defaultSource();
        var selected = SampleCorpus.verify(original).stream().filter(entry -> Set.of("P14", "P15").contains(entry.id())).toList();
        assertEquals(2, selected.size());
        Path batch = Files.createDirectory(directory.resolve("样本批次"));
        Path folder = Files.createDirectory(batch.resolve(selected.getFirst().caseFolder()));
        for (var entry : selected) {
            Path source = original.resolve(entry.relativePath());
            Files.copy(source, folder.resolve(source.getFileName()));
        }
        var info = service.cases().openCase(batch, folder);
        try (var ticket = service.scans().begin(info.id(), info.revision())) {
            return service.drafts().mergeScan(service.scans().scan(ticket));
        }
    }

    private static Record seedLegacyTravelPair(Services service, CaseView draft, Path output) throws IOException {
        var saved = service.exports().generate(draft.info().id(), draft.info().revision(), output, "legacy-travel");
        assertEquals(State.SUCCEEDED, saved.state(), saved.message());
        var snapshot = ExportSnapshot.fromDraft(draft);
        var rows = snapshot.rows();
        assertEquals(2, rows.size());
        var first = rows.getFirst();
        var last = rows.getLast();
        var legacyRow = new ExportSnapshot.ExpenseRow(first.itemId(), first.subtype(), first.startDate(), last.startDate(),
                "深圳", Map.of("route", "深圳", "vehicle", "飞机"), Map.of("TRANSPORT", 190_100L));
        var legacy = new ExportSnapshot(snapshot.caseId(), snapshot.caseName(), snapshot.caseFolder(), snapshot.type(),
                snapshot.revision(), snapshot.templateHash(), snapshot.ruleVersion(), List.of(legacyRow),
                snapshot.pages(), snapshot.unavailable(), snapshot.audit());
        var xlsx = saved.file(FileKind.XLSX).path();
        // Recreate the prior workbook layout inside the isolated fixture.
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(Files.readAllBytes(xlsx)))) {
            var sheet = book.getSheetAt(0);
            var outbound = sheet.getRow(2);
            outbound.getCell(0).setCellValue(first.startDate() + "\n" + last.startDate());
            outbound.getCell(1).setCellValue("深圳");
            outbound.getCell(3).setCellValue(1901.0);
            for (int col = 0; col < 10; col++) sheet.getRow(3).getCell(col).setBlank();
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (var stream = Files.newOutputStream(xlsx)) {
                book.write(stream);
            }
        }
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(legacy);
        String digest = TemplateArchive.sha256(xlsx);
        long size = Files.size(xlsx);
        Database.open(service.paths().databaseFile()).transaction(connection -> {
            update(connection, "UPDATE export_record SET snapshot_json=? WHERE export_id=?", json, saved.exportId());
            update(connection, "UPDATE export_file SET sha256=?,size=? WHERE export_id=? AND kind='XLSX'",
                    digest, size, saved.exportId());
            return null;
        });
        return service.exports().query(saved.exportId());
    }

    private static void assertLegacyTravelSheet(Record record) throws IOException {
        try (var input = Files.newInputStream(record.file(FileKind.XLSX).path());
             var workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("2026-11-12\n2026-11-13", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals(1901.0, sheet.getRow(2).getCell(3).getNumericCellValue());
            assertNull(sheet.getRow(3).getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
        }
    }

    private static void assertSplitTravelSheet(Record record) throws IOException {
        try (var input = Files.newInputStream(record.file(FileKind.XLSX).path());
             var workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheetAt(0);
            var outbound = sheet.getRow(2);
            var inbound = sheet.getRow(3);
            assertEquals(LocalDate.of(2026, 11, 12), outbound.getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(LocalDate.of(2026, 11, 13), inbound.getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals("宁波-深圳", outbound.getCell(1).getStringCellValue());
            assertEquals("深圳-宁波", inbound.getCell(1).getStringCellValue());
            assertEquals(796.0, outbound.getCell(3).getNumericCellValue());
            assertEquals(1105.0, inbound.getCell(3).getNumericCellValue());
            assertEquals(1901.0, sheet.getRow(10).getCell(10).getNumericCellValue());
        }
    }

    private static void seedLegacyUsdAmounts(AppPaths paths, String id) {
        Database.open(paths.databaseFile()).transaction(connection -> {
            update(connection, "UPDATE reimbursement_case SET rule_version='1' WHERE id=?", id);
            update(connection, "UPDATE document_page SET warnings=json_set(warnings,'$.version','3') WHERE case_id=?", id);
            update(connection, "UPDATE field_candidate SET parser_version='3' WHERE case_id=?", id);
            update(connection, "UPDATE expense_amount SET confirmed_cny_minor=NULL,basis=? WHERE case_id=?",
                    "原币收据待核定人民币金额", id);
            update(connection, "UPDATE expense_evidence SET basis='原始收据' WHERE case_id=?", id);
            // Publish the fixture before making its cached v3 USD field visible to the new guard.
            update(connection, "UPDATE field_candidate SET normalized=NULL WHERE case_id=? AND key='currency'", id);
            return null;
        });
    }

    private static Double otherAmount(Record record) throws IOException {
        try (var input = Files.newInputStream(record.file(FileKind.XLSX).path());
             var workbook = new XSSFWorkbook(input)) {
            var cell = workbook.getSheetAt(0).getRow(3).getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            return cell == null ? null : cell.getNumericCellValue();
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static void verifyPair(Services service, Record result) throws IOException {
        assertEquals(2, result.files().size());
        try (var entries = Files.list(result.finalDirectory())) {
            assertEquals(2, entries.count());
        }
        for (var file : result.files()) {
            assertEquals(file.path(), service.exports().verifiedFile(result.exportId(), file.kind()));
            assertEquals(file.size(), Files.size(file.path()));
            assertEquals(file.sha256(), TemplateArchive.sha256(file.path()));
        }
    }

    private static Record interruptedAtManifest(Fixture fixture) {
        var stops = services(fixture.directory(), null, ExportPublisher::atomicMove, (point, record) -> {
            if (point == Boundary.MANIFEST_COMMITTED) {
                throw new SimulatedStop();
            }
        });
        assertThrows(SimulatedStop.class, () -> stops.exports().generate(
                fixture.caseId(), fixture.revision(), fixture.destination(), "pending"));
        return fixture.exports().history(fixture.caseId()).getFirst();
    }

    private static Path staging(Record record) {
        return record.finalDirectory().resolveSibling(ExportPublisher.STAGE_PREFIX + record.exportId());
    }

    private Fixture fixture() throws Exception {
        Path directory = testDirectory.toRealPath();
        var service = services(directory, null, ExportPublisher::atomicMove, (point, record) -> {});
        Path batch = Files.createDirectory(directory.resolve("样本批次"));
        Path folder = Files.createDirectory(batch.resolve("其他报销"));
        Path source = folder.resolve("测试凭证.png");
        var picture = new BufferedImage(180, 110, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(picture, "PNG", source.toFile()));
        var info = service.cases().openCase(batch, folder);
        CaseView view;
        try (var ticket = service.scans().begin(info.id(), info.revision())) {
            view = service.drafts().mergeScan(service.scans().scan(ticket));
        }
        assertEquals(1, view.attachments().size());
        Files.createDirectories(service.paths().cacheDirectory().resolve("ocr"));
        return new Fixture(directory, service, info.id(), view.info().revision(), source, service.paths().defaultOutputRoot());
    }

    private static Services services(Path directory, Path cache, ExportPublisher.Move move, ExportService.Checkpoint callback) {
        var paths = new AppPaths(directory, directory.resolve("app-data"),
                cache != null ? cache : directory.resolve("app-cache"), directory.resolve("成品目录"));
        var db = Database.open(paths.databaseFile());
        var archive = new TemplateArchive(paths.dataDirectory());
        archive.installBundled();
        var policy = new PathPolicy(db);
        var cases = new CaseService(db, policy);
        var jobs = new CaseJobs();
        var scans = new ScanService(cases, policy, jobs);
        var drafts = new DraftService(db, jobs, RecognitionService.fromPaths(paths));
        var output = new ExportService(db, jobs, policy, new TemplateCatalog(archive), paths, move, callback);
        return new Services(paths, cases, scans, drafts, output);
    }

    private static Process startProbe(Fixture fixture, Boundary boundary) throws IOException {
        String launcher = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", launcher).toString(),
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), CrashProbe.class.getName(),
                fixture.directory().toString(), fixture.caseId(), Long.toString(fixture.revision()),
                fixture.destination().toString(), boundary.name()));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(fixture.directory().resolve("crash-probe.log").toFile())
                .start();
    }

    private static void awaitCheckpoint(Path directory, Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Path ready = directory.resolve("checkpoint");
        while (!Files.exists(ready) && process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(ready), () -> {
            try {
                return Files.readString(directory.resolve("crash-probe.log"));
            } catch (IOException failure) {
                return failure.getMessage();
            }
        });
    }

    public static final class CrashProbe {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            try (var lock = InstanceLock.acquire(directory.resolve("app-data"))) {
                Boundary stopAt = Boundary.valueOf(args[4]);
                var service = services(directory, null, ExportPublisher::atomicMove, (point, record) -> {
                    if (point == stopAt) {
                        try {
                            Files.createFile(directory.resolve("checkpoint"));
                            new CountDownLatch(1).await();
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    }
                });
                service.exports().generate(args[1], Long.parseLong(args[2]), Path.of(args[3]), "interrupted");
            }
        }
    }

    private static final class SimulatedStop extends Error {}
    private record Services(AppPaths paths, CaseService cases, ScanService scans, DraftService drafts, ExportService exports) {}
    private record Fixture(Path directory, Services services, String caseId, long revision, Path source, Path destination) {
        ExportService exports() { return services.exports(); }
        AppPaths paths() { return services.paths(); }
        CaseService cases() { return services.cases(); }
        Record generate(String id) { return exports().generate(caseId, revision, destination, id); }
    }
}
