package expenseassistant.acceptance;

import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.draft.CaseView;
import expenseassistant.draft.DraftService;
import expenseassistant.intake.PathPolicy;
import expenseassistant.intake.ScanService;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.output.ExportService;
import expenseassistant.output.ExportSnapshot;
import expenseassistant.output.TemplateArchive;
import expenseassistant.output.TemplateCatalog;
import expenseassistant.output.WordWriter;
import expenseassistant.platform.AppPaths;
import expenseassistant.recognition.OcrWorkspace;
import expenseassistant.recognition.PageParser;
import expenseassistant.recognition.RecognitionService;
import expenseassistant.recognition.TesseractRunner;
import expenseassistant.store.Database;
import expenseassistant.store.InstanceLock;
import expenseassistant.support.ReceiptFixtures;
import expenseassistant.support.SampleCorpus;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static expenseassistant.output.ExportService.FileKind.DOCX;
import static expenseassistant.output.ExportService.FileKind.XLSX;
import static expenseassistant.output.ExportService.State.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.*;

class CaseWorkflowIT {
    @TempDir Path temporary;

    @Test
    void importReviewExportReopen() throws Exception {
        Path batch = temporary.resolve("样本批次");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        String evidence = System.getProperty("expenseassistant.acceptanceOutput");
        Path output = evidence == null ? temporary.resolve("成品") : Path.of(evidence).toAbsolutePath().normalize();
        SampleCorpus.checkOutsideSource(SampleCorpus.defaultSource(), output);
        var app = new AppPaths(temporary, temporary.resolve("data"), temporary.resolve("cache"), output);
        String id;
        String exportId;
        long revision;
        try (var ignored = InstanceLock.acquire(app.dataDirectory())) {
            var services = services(app);
            var info = services.cases.openCase(batch, batch.resolve("杭州出差-2026.11.03"));
            id = info.id();
            var view = scan(services, info);
            revision = view.info().revision();
            assertEquals(15_879L, view.confirmedTotal());
            assertEquals(2, view.items().size());
            assertEquals(8, view.attachments().size());
            assertEquals(5, view.attachments().subList(0, 5).stream()
                    .filter(page -> page.relativePath().startsWith("通行费电子发票/")).count());
            assertTrue(view.attachments().get(5).relativePath().contains("汇总单"));
            assertTrue(view.attachments().get(6).relativePath().contains("汇总单"));
            assertTrue(view.attachments().get(7).relativePath().contains("停车"));
            var record = services.export.generate(id, revision, output, "a03-original");
            exportId = record.exportId();
            assertEquals(SUCCEEDED, record.state(), record.message());
            assertEquals(2, record.files().size());
            try (var names = Files.list(record.finalDirectory())) {
                assertEquals(2, names.count());
            }
            assertEquals(exportId, services.export.generate(id, revision, output, "a03-original").exportId());
            verifyOriginalPair(services, view, exportId);

            var missing = view.attachments().getFirst();
            Files.delete(info.folder().resolve(missing.relativePath()));
            var updated = scan(services, view.info());
            assertEquals(revision + 1, updated.info().revision());
            assertEquals(3_000L, updated.confirmedTotal());
            assertEquals(exportId, updated.info().currentExportId());
            assertEquals("SOURCE_CHANGED", assertThrows(CaseException.class, () -> services.export.generate(
                    id, updated.info().revision(), temporary.resolve("缺票对照成品"), "a03-one-missing")).code());
            assertEquals(1, services.export.history(id).size());
            var excluded = services.drafts.apply(id, updated.info().revision(), List.of(
                    new DraftService.SetAttachment(missing.docId(), 1, 1, missing.caption(), missing.layout(), null,
                            "样本副本缺票，核对后排除")));
            assertEquals(revision + 2, excluded.info().revision());
            assertEquals(3_000L, excluded.confirmedTotal());
            var reduced = services.export.generate(id, excluded.info().revision(),
                    temporary.resolve("缺票对照成品"), "a03-one-missing");
            assertEquals(SUCCEEDED, reduced.state(), reduced.message());
            try (var file = Files.newInputStream(services.export.verifiedFile(reduced.exportId(), XLSX));
                 var workbook = new XSSFWorkbook(file)) {
                assertEquals(3_000L, cents(workbook.getSheetAt(0).getRow(10).getCell(10).getNumericCellValue()));
            }
            assertEquals(2, services.export.history(id).size());
            assertEquals(reduced.exportId(), services.drafts.load(id).info().currentExportId());
        }
        try (var ignored = InstanceLock.acquire(app.dataDirectory())) {
            var reopened = services(app);
            reopened.export.recover();
            assertEquals(revision + 2, reopened.drafts.load(id).info().revision());
            var original = reopened.export.query(exportId);
            assertEquals(SUCCEEDED, original.state());
            assertEquals(revision, original.revision());
            for (var published : original.files()) {
                assertEquals(published.sha256(), TemplateArchive.sha256(
                        reopened.export.verifiedFile(exportId, published.kind())));
            }
            if (evidence != null) {
                System.out.printf("A03 evidence revision=%d XLSX=%s DOCX=%s%n", revision,
                        original.file(XLSX).path(), original.file(DOCX).path());
            }
        }
    }

    @Test
    void twoControlledUsdReceiptsShareTheConvertedDraftAndPrintedPair() throws Exception {
        Path folder = Files.createDirectories(temporary.resolve("批次/其他报销"));
        ReceiptFixtures.receipt(folder.resolve("1.pdf"), "Example Shop", "REC-001", "USD 100.00");
        ReceiptFixtures.receipt(folder.resolve("2.pdf"), "Example Shop", "REC-002", "US$100.00");
        var app = new AppPaths(temporary, temporary.resolve("data"), temporary.resolve("cache"), temporary.resolve("成品"));
        var services = servicesWithoutOcr(app);
        var view = scan(services, services.cases.openCase(folder));
        assertForeignPair(services, app, view, 67000L);

        Path cnyFolder = Files.createDirectory(folder.getParent().resolve("其他费用"));
        var fixture = SampleCorpus.verify(SampleCorpus.defaultSource()).stream()
                .filter(entry -> entry.relativePath().contains("杭州停车费")).findFirst().orElseThrow();
        Files.copy(SampleCorpus.defaultSource().resolve(fixture.relativePath()), cnyFolder.resolve("停车.pdf"));
        var cny = scan(services, services.cases.openCase(cnyFolder));
        assertEquals(3000L, cny.confirmedTotal());
        var record = services.export.generate(cny.info().id(), cny.info().revision(), app.defaultOutputRoot(), "cny-control");
        assertEquals(SUCCEEDED, record.state(), record.message());
        try (var input = Files.newInputStream(services.export.verifiedFile(record.exportId(), XLSX));
             var workbook = new XSSFWorkbook(input)) {
            assertEquals(3000L, cents(workbook.getSheetAt(0).getRow(3).getCell(3).getNumericCellValue()));
        }
    }

    @Test
    void unconfirmedForeignReceiptsKeepTheRmbCellsAndTotalEmpty() throws Exception {
        Path folder = Files.createDirectories(temporary.resolve("批次/其他报销"));
        ReceiptFixtures.receipt(folder.resolve("1.pdf"), "Example Shop", "REC-001", "$100.00");
        ReceiptFixtures.receipt(folder.resolve("2.pdf"), "Example Shop", "REC-002", "CAD 100.00");
        var app = new AppPaths(temporary, temporary.resolve("data"), temporary.resolve("cache"), temporary.resolve("成品"));
        var services = servicesWithoutOcr(app);
        var view = scan(services, services.cases.openCase(folder));
        assertForeignPair(services, app, view, null);
    }

    @Test
    void realAppleReceiptsUseLocalOcrAndConvertBothOriginalAmounts() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("expenseassistant.ocrRoot") != null,
                "需使用经校验的本机 OCR 组件");
        Path batch = temporary.resolve("样本批次");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        var app = new AppPaths(temporary, temporary.resolve("data"), temporary.resolve("cache"), temporary.resolve("成品"));
        try (var ignored = InstanceLock.acquire(app.dataDirectory())) {
            var services = services(app);
            var info = services.cases.openCase(batch, batch.resolve("其他报销"));
            assertForeignPair(services, app, scan(services, info), 67000L);
        }
    }

    private static void assertForeignPair(Services services, AppPaths app, CaseView view, Long minor) throws Exception {
        assertEquals(2, view.items().size());
        assertEquals(2, view.attachments().size());
        assertTrue(view.claims().isEmpty());
        var snapshot = ExportSnapshot.fromDraft(view);
        if (minor != null) {
            assertEquals(2 * minor, view.confirmedTotal());
            assertTrue(view.items().stream().allMatch(item -> item.reviewReasons().isEmpty()));
            assertEquals(2, snapshot.audit().amounts().size());
            assertTrue(snapshot.audit().amounts().stream().allMatch(amount -> "USD".equals(amount.currency())
                    && "100.00".equals(amount.faceValue()) && minor.equals(amount.confirmedMinor())
                    && "USD 100.00 × 6.7".equals(amount.basis())));
            assertEquals(2, snapshot.audit().evidence().stream()
                    .filter(evidence -> "USD 100.00 × 6.7".equals(evidence.basis())).count());
        } else {
            assertNull(view.confirmedTotal());
            assertTrue(view.items().stream().allMatch(item -> item.reviewReasons().contains("CNY_CONFIRMATION_REQUIRED")));
        }
        var result = services.export.generate(view.info().id(), view.info().revision(), app.defaultOutputRoot(), "foreign");
        assertEquals(SUCCEEDED, result.state(), result.message());
        try (var input = Files.newInputStream(services.export.verifiedFile(result.exportId(), XLSX));
             var workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheetAt(0);
            var total = sheet.getRow(9).getCell(3);
            assertEquals(CellType.FORMULA, total.getCellType());
            if (minor == null) {
                assertNull(sheet.getRow(3).getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                assertNull(sheet.getRow(4).getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
                assertEquals(CellType.STRING, total.getCachedFormulaResultType());
                assertEquals("", total.getStringCellValue());
            } else {
                assertEquals(minor, cents(sheet.getRow(3).getCell(3).getNumericCellValue()));
                assertEquals(minor, cents(sheet.getRow(4).getCell(3).getNumericCellValue()));
                assertEquals(CellType.NUMERIC, total.getCachedFormulaResultType());
                assertEquals(2 * minor, cents(total.getNumericCellValue()));
            }
        }
        new WordWriter().verify(snapshot, services.export.verifiedFile(result.exportId(), DOCX));
    }

    @Test
    void otherFrozenTravelAndTaxiCasesGenerateVerifiedPairs() throws Exception {
        Path batch = temporary.resolve("样本批次");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        var app = new AppPaths(temporary, temporary.resolve("data"), temporary.resolve("cache"), temporary.resolve("成品"));
        record Expected(String folder, long amount, String totalCell, int pages) {}
        var inputs = List.of(new Expected("杭州出差-2026.10.21", 38_275, "K11", 3),
                new Expected("深圳出差-2026.11.12~13", 247_230, "K11", 5),
                new Expected("交通费", 13_100, "F12", 2));
        try (var ignored = InstanceLock.acquire(app.dataDirectory())) {
            var services = services(app);
            assertEquals(5, new PathPolicy(Database.open(app.databaseFile())).listCases(batch).size());
            for (Expected expected : inputs) {
                var info = services.cases.openCase(batch.resolve(expected.folder()));
                var view = scan(services, info);
                assertEquals(expected.amount(), view.confirmedTotal(), expected.folder());
                assertEquals(expected.pages(), view.attachments().size(), expected.folder());
                var result = services.export.generate(info.id(), view.info().revision(), app.defaultOutputRoot(), "corpus");
                assertEquals(SUCCEEDED, result.state(), result.message());
                try (var input = Files.newInputStream(services.export.verifiedFile(result.exportId(), XLSX));
                     var workbook = new XSSFWorkbook(input)) {
                    var total = new org.apache.poi.ss.util.CellReference(expected.totalCell());
                    assertEquals(expected.amount(), cents(workbook.getSheetAt(0).getRow(total.getRow())
                            .getCell(total.getCol()).getNumericCellValue()), expected.folder());
                    if (expected.folder().startsWith("深圳出差")) {
                        verifyShenzhen(workbook.getSheetAt(0));
                    }
                }
                new WordWriter().verify(ExportSnapshot.fromDraft(view),
                        services.export.verifiedFile(result.exportId(), DOCX));
            }
        }
    }

    @Test
    void invalidTravelAmountCannotReplaceTheLastPublishedPair() throws Exception {
        Path batch = temporary.resolve("样本批次");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        var app = new AppPaths(temporary, temporary.resolve("data"), temporary.resolve("cache"), temporary.resolve("成品"));
        try (var ignored = InstanceLock.acquire(app.dataDirectory())) {
            var services = services(app);
            var info = services.cases.openCase(batch.resolve("杭州出差-2026.11.03"));
            var view = scan(services, info);
            var original = services.export.generate(info.id(), view.info().revision(), app.defaultOutputRoot(), "original");
            assertEquals(SUCCEEDED, original.state(), original.message());
            var edited = services.drafts.apply(info.id(), view.info().revision(), List.of(
                    new DraftService.SetAmount(view.items().getFirst().id(), Component.TRANSPORT, "CNY",
                            Long.MAX_VALUE, "验收溢出")));
            var failure = assertThrows(CaseException.class, () -> services.export.generate(
                    info.id(), edited.info().revision(), app.defaultOutputRoot(), "out-of-range"));
            assertEquals("AMOUNT_OUT_OF_RANGE", failure.code());
            assertEquals(original.exportId(), services.drafts.load(info.id()).info().currentExportId());
            assertEquals(1, services.export.history(info.id()).size());
            for (var file : original.files()) {
                assertEquals(file.sha256(), TemplateArchive.sha256(services.export.verifiedFile(original.exportId(), file.kind())));
            }
        }
    }

    @Test
    void privateCarPairUsesTheSelectedMileageFuelAndVerifiedAttachments() throws Exception {
        var app = privateCarApp();
        var services = services(app);
        var view = mileageCase(services, 2);
        assertEquals(CaseService.ruleVersionFor(CaseType.PRIVATE_CAR), view.info().ruleVersion());
        var id = view.info().id();
        services.database.transaction(connection -> {
            saveMileagePage(connection, id, view.sources().get(0).docId(), "167", "3");
            saveMileagePage(connection, id, view.sources().get(1).docId(), "69", PageParser.VERSION);
            return null;
        });
        var selected = services.drafts.apply(id, view.info().revision(), List.of());
        assertEquals(21_240L, selected.confirmedTotal());
        assertEquals(List.of(15_030L, 6_210L), fuelBases(selected).stream().map(ExportSnapshot.AmountBasis::confirmedMinor).toList());
        assertTrue(fuelBases(selected).stream().allMatch(basis -> basis.basis().startsWith("选中路线")));
        verifyPrivateCarPair(services, app, selected, "mileage-pair", Map.of("E4", 167d, "F4", 150.30,
                "E5", 69d, "F5", 62.10, "E9", 236d, "F9", 212.40, "I9", 212.40), List.of("G4", "H4"));

        String row = selected.items().getFirst().id();
        services.database.transaction(connection -> {
            update(connection, "UPDATE reimbursement_case SET rule_version='2' WHERE id=?", id);
            return null;
        });
        var ruleTwo = services.drafts.load(id);
        assertEquals(ruleTwo, services.drafts.loadForReview(id));
        var supplemented = services.drafts.apply(id, selected.info().revision(), List.of(
                new DraftService.SetField(row, "description", "核定私车用途"),
                new DraftService.SetAmount(row, Component.TOLL, "CNY", 1000L, "受控过路费"),
                new DraftService.SetAmount(row, Component.PARKING, "CNY", 500L, "受控停车费")));
        assertEquals("3", supplemented.info().ruleVersion());
        assertEquals(row, supplemented.items().getFirst().id());
        assertEquals("167", supplemented.items().getFirst().fields().get("distanceKm"));
        assertFalse(supplemented.items().getFirst().reviewReasons().contains("MILEAGE_NEEDS_REVIEW"));
        assertEquals(22_740L, supplemented.confirmedTotal());
        verifyPrivateCarPair(services, app, supplemented, "mileage-with-fees", Map.of("G4", 10d, "H4", 5d,
                "I4", 165.30, "G9", 10d, "H9", 5d, "I9", 227.40), List.of());
    }

    @Test
    void openingLegacyMileageRecalculatesOnceBeforeTheNextScan() throws Exception {
        var app = privateCarApp();
        var services = services(app);
        var current = mileageCase(services, 3);
        var legacy = seedLegacyMileage(services, current);
        String id = legacy.info().id();
        long revision = legacy.info().revision();
        var recorded = legacy.overrides().stream().filter(value -> value.fieldKey().equals("AMOUNT:FUEL"))
                .findFirst().orElseThrow();
        assertEquals(9900L, legacy.confirmedTotal());
        assertTrue(legacy.sources().stream().allMatch(source -> "2".equals(source.pages().getFirst().parserVersion())));
        assertEquals("MILEAGE_RULE_REFRESH_REQUIRED", assertThrows(CaseException.class, () ->
                services.export.generate(id, revision, app.defaultOutputRoot(), "legacy-before-review")).code());
        try (var ticket = services.scan.begin(id, revision)) {
            assertEquals("BUSY", assertThrows(CaseException.class, () -> services.drafts.loadForReview(id)).code());
            assertEquals(legacy, services.drafts.load(id));
        }

        var updated = services.drafts.loadForReview(id);
        assertEquals(revision + 1, updated.info().revision());
        assertEquals(legacy.info().scanGeneration(), updated.info().scanGeneration());
        assertEquals(CaseService.ruleVersionFor(CaseType.PRIVATE_CAR), updated.info().ruleVersion());
        assertEquals(updated, services.drafts.loadForReview(id));
        assertEquals(legacy.overrides(), updated.overrides());
        assertTrue(updated.overrides().contains(recorded));
        assertTrue(recorded.value().contains("9900"));
        assertEquals(List.of(legacy.sources().get(1).sha256()), recorded.sourceHashes());
        assertEquals(revision, recorded.updatedRevision());
        assertTrue(updated.items().getFirst().reviewReasons().contains("MILEAGE_NEEDS_REVIEW"));
        assertFalse(updated.items().getLast().reviewReasons().contains("MILEAGE_NEEDS_REVIEW"));
        var fees = fuelBases(updated);
        assertEquals(1800L, updated.confirmedTotal());
        assertEquals(1, fees.size());
        assertEquals(1800L, fees.getFirst().confirmedMinor());
        assertTrue(fees.getFirst().basis().startsWith("人工确认里程"));
        verifyPrivateCarPair(services, app, updated, "legacy-after-review", Map.of("E5", 20d, "F5", 18d,
                "E9", 20d, "F9", 18d, "I9", 18d), List.of("E4", "F4", "E6", "F6"));
    }

    private AppPaths privateCarApp() {
        return new AppPaths(temporary, temporary.resolve("data"), temporary.resolve("cache"), temporary.resolve("成品"));
    }

    private CaseView mileageCase(Services services, int count) throws IOException {
        var folder = Files.createDirectories(temporary.resolve("样本批次/私车公用"));
        for (int index = 1; index <= count; index++) {
            var image = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
            image.setRGB(70, 40, index * 0x373737);
            assertTrue(ImageIO.write(image, "png", folder.resolve("甲-乙-2026110" + index + ".png").toFile()));
        }
        return scan(services, services.cases.openCase(folder));
    }

    private static CaseView seedLegacyMileage(Services services, CaseView fixture) {
        var info = fixture.info();
        services.database.transaction(connection -> {
            update(connection, "UPDATE reimbursement_case SET rule_version='1' WHERE id=?", info.id());
            for (var source : fixture.sources()) {
                saveMileagePage(connection, info.id(), source.docId(), "42", "2");
            }
            var automatic = fixture.items().getFirst().id();
            var manual = fixture.items().get(1).id();
            var cleared = fixture.items().getLast().id();
            update(connection, """
                    INSERT INTO item_field(case_id,item_id,key,typed_value,state) VALUES
                        (?,?,'rawDistanceKm','42','AUTO'),(?,?,'distanceKm','42','AUTO'),(?,?,'distanceKm','20','CONFIRMED')
                    """, info.id(), automatic, info.id(), automatic, info.id(), manual);
            update(connection, """
                    INSERT INTO expense_amount(case_id,item_id,component,original_currency,confirmed_cny_minor,basis)
                    VALUES(?,?,'FUEL','CNY',9900,'旧版人工油费')
                    """, info.id(), manual);
            update(connection, """
                    INSERT INTO field_override(case_id,target_id,field_key,action,value,source_hashes,updated_revision) VALUES
                        (?,?,'distanceKm','SET','20','[]',?),(?,?,'distanceKm','CLEAR',NULL,'[]',?),
                        (?,?,'AMOUNT:FUEL','SET','{"minor":9900,"basis":"旧版人工油费"}',?,?)
                    """, info.id(), manual, info.revision(), info.id(), cleared, info.revision(),
                    info.id(), manual, "[\"" + fixture.sources().get(1).sha256() + "\"]", info.revision());
            return null;
        });
        return services.drafts.load(info.id());
    }

    private static void saveMileagePage(Connection connection, String caseId, String docId, String km, String parser)
            throws SQLException {
        update(connection, "UPDATE document_page SET kind='MILEAGE',warnings=? WHERE doc_id=? AND page_no=1",
                "{\"warnings\":[],\"version\":\"" + parser + "\"}", docId);
        update(connection, "UPDATE evidence_unit SET kind='MILEAGE' WHERE doc_id=?", docId);
        update(connection, "DELETE FROM field_candidate WHERE doc_id=? AND page_no=1", docId);
        update(connection, """
                INSERT INTO field_candidate(candidate_id,case_id,doc_id,page_no,key,raw,normalized,method,bbox,validation,parser_version)
                VALUES(?,?,?,1,?,?,?,'OCR','{"x":0.1,"y":0.7,"width":0.1,"height":0.05}','受控识别',?)
                """, docId + "-mileage", caseId, docId, "2".equals(parser) ? "distanceKm" : "selectedRouteDistanceKm",
                km + "公里", km, parser);
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static List<ExportSnapshot.AmountBasis> fuelBases(CaseView view) {
        return ExportSnapshot.fromDraft(view).audit().amounts().stream()
                .filter(amount -> amount.component() == Component.FUEL).toList();
    }

    private static void verifyPrivateCarPair(Services services, AppPaths app, CaseView view, String request,
                                             Map<String, Double> expected, List<String> empty) throws IOException {
        var record = services.export.generate(view.info().id(), view.info().revision(), app.defaultOutputRoot(), request);
        assertEquals(SUCCEEDED, record.state(), record.message());
        assertEquals(2, record.files().size());
        try (var input = Files.newInputStream(services.export.verifiedFile(record.exportId(), XLSX));
             var workbook = new XSSFWorkbook(input)) {
            var sheet = workbook.getSheetAt(0);
            assertTrue(sheet.getColumnWidth(0) >= 14 * 256, "WPS 预览需显示完整的 yyyy-mm-dd 日期");
            assertEquals(view.items().getFirst().startDate(),
                    sheet.getRow(3).getCell(0).getLocalDateTimeCellValue().toLocalDate());
            for (var entry : expected.entrySet()) {
                var ref = new org.apache.poi.ss.util.CellReference(entry.getKey());
                var cell = sheet.getRow(ref.getRow()).getCell(ref.getCol());
                assertEquals(entry.getValue(), cell.getNumericCellValue(), 0.000001, entry.getKey());
                if (ref.getRow() == 8 || ref.getCol() == 8) {
                    assertEquals(CellType.FORMULA, cell.getCellType());
                    assertEquals(CellType.NUMERIC, cell.getCachedFormulaResultType());
                }
            }
            for (var address : empty) {
                var ref = new org.apache.poi.ss.util.CellReference(address);
                assertNull(sheet.getRow(ref.getRow()).getCell(ref.getCol(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            }
        }
        var snapshot = ExportSnapshot.fromDraft(view);
        assertEquals(view.sources().size(), snapshot.pages().size());
        assertTrue(snapshot.pages().stream().allMatch(page -> page.sourcePage() == 1));
        new WordWriter().verify(snapshot, services.export.verifiedFile(record.exportId(), DOCX));
    }

    private static void verifyShenzhen(XSSFSheet sheet) {
        var row = sheet.getRow(2);
        assertEquals("2026-11-12\n2026-11-13", row.getCell(0).getStringCellValue());
        assertTrue(List.of("宁波->深圳", "深圳").contains(row.getCell(1).getStringCellValue()));
        assertEquals("飞机", row.getCell(2).getStringCellValue());
        for (var entry : Map.of(3, 190_100L, 5, 50_000L, 6, 7_130L, 10, 247_230L).entrySet()) {
            assertEquals(entry.getValue(), cents(row.getCell(entry.getKey()).getNumericCellValue()));
            assertEquals(entry.getValue(), cents(sheet.getRow(10).getCell(entry.getKey()).getNumericCellValue()));
        }
        for (int column : new int[]{4, 7, 8, 9}) {
            assertNull(row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            assertEquals("", sheet.getRow(10).getCell(column).getStringCellValue());
        }
        for (int index = 3; index < 10; index++) {
            for (int column = 0; column < 10; column++) {
                assertNull(sheet.getRow(index).getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            }
            assertEquals("", sheet.getRow(index).getCell(10).getStringCellValue());
        }
    }

    private static void verifyOriginalPair(Services services, CaseView view, String exportId) throws IOException {
        var xlsx = services.export.verifiedFile(exportId, XLSX);
        try (var input = Files.newInputStream(xlsx); var workbook = new XSSFWorkbook(input)) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheetAt(0);
            assertEquals("1、差旅费明细表-空白", sheet.getSheetName());
            for (var expected : Map.of("D3", 15_879L, "K11", 15_879L).entrySet()) {
                var cellRef = new org.apache.poi.ss.util.CellReference(expected.getKey());
                var cell = sheet.getRow(cellRef.getRow()).getCell(cellRef.getCol());
                assertEquals(expected.getValue(), cents(cell.getNumericCellValue()), expected.getKey());
            }
            assertNull(sheet.getRow(3).getCell(3, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
        }
        var docx = services.export.verifiedFile(exportId, DOCX);
        var snapshot = ExportSnapshot.fromDraft(view);
        assertEquals(4, expenseassistant.output.AttachmentLayout.paginate(snapshot.pages()).size());
        new WordWriter().verify(snapshot, docx);
    }

    private static long cents(double value) {
        return BigDecimal.valueOf(value).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static CaseView scan(Services services, CaseService.CaseInfo info) {
        try (var ticket = services.scan.begin(info.id(), info.revision())) {
            return services.drafts.mergeScan(services.scan.scan(ticket));
        }
    }

    private static Services services(AppPaths app) {
        return services(app, RecognitionService.fromPaths(app));
    }

    private static Services servicesWithoutOcr(AppPaths app) {
        var workspace = new OcrWorkspace(app.cacheDirectory());
        var runner = new TesseractRunner(app.installDirectory().resolve("unavailable-ocr"), app.cacheDirectory(), workspace);
        return services(app, new RecognitionService(runner, workspace));
    }

    private static Services services(AppPaths app, RecognitionService recognition) {
        var archive = new TemplateArchive(app.dataDirectory());
        archive.installBundled();
        var database = Database.open(app.databaseFile());
        var paths = new PathPolicy(database);
        var jobs = new CaseJobs();
        var cases = new CaseService(database, paths);
        var scans = new ScanService(cases, paths, jobs);
        var drafts = new DraftService(database, jobs, recognition);
        var exports = new ExportService(database, jobs, paths, new TemplateCatalog(archive), app);
        return new Services(cases, scans, drafts, exports, database);
    }

    private record Services(CaseService cases, ScanService scan, DraftService drafts, ExportService export, Database database) {}
}
