package expenseassistant.ui;

import java.awt.image.BufferedImage;

import javax.imageio.ImageIO;

import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.draft.CaseView;
import expenseassistant.draft.DraftService;
import expenseassistant.intake.PathPolicy;
import expenseassistant.intake.ScanService;
import expenseassistant.output.ExportService;
import expenseassistant.output.TemplateArchive;
import expenseassistant.output.TemplateCatalog;
import expenseassistant.platform.AppPaths;
import expenseassistant.platform.AppVersion;
import expenseassistant.platform.WpsLauncher;
import expenseassistant.recognition.AttachmentImages;
import expenseassistant.recognition.AttachmentImages.CropMode;
import expenseassistant.recognition.PageImages;
import expenseassistant.recognition.PageParser;
import expenseassistant.recognition.RecognitionService;
import expenseassistant.store.Database;
import expenseassistant.support.FxTestSession;
import expenseassistant.support.ReceiptFixtures;
import expenseassistant.support.SampleCorpus;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchFlowIT {
    @TempDir Path temporary;

    @Test
    void scansHungzhouPublishesCurrentVersionAndDisablesOldOutputsAfterEdit() throws Exception {
        Path batch = temporary.resolve("受控批次");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        var service = services();
        try (var fixture = show(service)) {
            var workbench = fixture.workbench;
            Path folder = batch.resolve("杭州出差-2026.11.03");
            FxTestSession.runOnFxThread(() -> {
                assertAppVersion(workbench);
                assertTrue(button(workbench, "expense-generate").isDisabled());
                assertTrue(button(workbench, "expense-open-excel").isDisabled());
                workbench.selectCase(folder);
                assertTrue(button(workbench, "expense-choose").isDisabled());
                assertTrue(button(workbench, "expense-cancel").isVisible());
            });
            await(() -> !button(workbench, "expense-generate").isDisabled());
            var info = service.cases.openCase(batch, folder);
            FxTestSession.runOnFxThread(() -> {
                assertEquals("¥158.79", label(workbench, "expense-total").getText());
                assertAppVersion(workbench);
                assertEquals(2, ((TableView<?>) workbench.lookup("#expense-rows")).getItems().size());
                assertFalse(((ComboBox<?>) workbench.lookup("#expense-document")).getItems().isEmpty());
                button(workbench, "expense-generate").fire();
                assertTrue(button(workbench, "expense-choose").isDisabled());
            });
            await(() -> !button(workbench, "expense-open-excel").isDisabled());
            var published = service.exports.history(info.id()).getFirst();
            assertEquals(ExportService.State.SUCCEEDED, published.state());
            FxTestSession.runOnFxThread(() -> {
                assertFalse(button(workbench, "expense-open-word").isDisabled());
                assertEquals("已生成 · 修订 " + published.revision(), label(workbench, "expense-export-status").getText());
            });
            Path generatedWord = published.file(ExportService.FileKind.DOCX).path();
            byte[] originalWord = Files.readAllBytes(generatedWord);
            Files.writeString(generatedWord, "controlled external edit");
            FxTestSession.runOnFxThread(workbench::refreshOutputs);
            await(() -> button(workbench, "expense-open-word").isDisabled()
                    && !button(workbench, "expense-open-excel").isDisabled());
            Files.write(generatedWord, originalWord);
            FxTestSession.runOnFxThread(workbench::refreshOutputs);
            await(() -> !button(workbench, "expense-open-word").isDisabled());

            var draft = service.drafts.load(info.id());
            var page = draft.attachments().getFirst();
            FxTestSession.runOnFxThread(() -> workbench.save(new EvidenceEditor.Edit(
                    List.of(new DraftService.SetCaption(page.docId(), page.sourcePage(), "与原件已核对")), "附件说明 · 与原件已核对")));
            await(() -> !workbench.busy() && label(workbench, "expense-export-status").getText().contains("草稿已更新"));
            FxTestSession.runOnFxThread(() -> {
                assertAppVersion(workbench);
                assertEquals("已保存修改", label(workbench, "expense-progress-text").getText());
                assertTrue(label(workbench, "expense-export-status").getText().endsWith("修订 " + published.revision()));
                assertTrue(button(workbench, "expense-open-excel").isDisabled());
                assertTrue(button(workbench, "expense-open-word").isDisabled());
                assertFalse(button(workbench, "expense-history").isDisabled());
                assertEquals("生成历史", button(workbench, "expense-history").getText());
            });
            assertEquals(info.revision() + 1, service.drafts.load(info.id()).info().revision());
            assertTrue(Files.isRegularFile(service.exports.verifiedFile(published.exportId(), ExportService.FileKind.XLSX)));
        }
    }

    @Test
    void controlledUsdReceiptsDisplayTheirConvertedRowsAndTotal() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("其他报销"));
        ReceiptFixtures.receipt(folder.resolve("1.pdf"), "Example Shop", "REC-001", "USD 100.00");
        ReceiptFixtures.receipt(folder.resolve("2.pdf"), "Example Shop", "REC-002", "US$100.00");
        var service = services();
        try (var fixture = show(service)) {
            var workbench = fixture.workbench;
            FxTestSession.runOnFxThread(() -> workbench.selectCase(folder));
            await(() -> !workbench.busy() && "¥1340.00".equals(label(workbench, "expense-total").getText()));
            FxTestSession.runOnFxThread(() -> {
                var rows = (TableView<?>) workbench.lookup("#expense-rows");
                assertEquals(2, rows.getItems().size());
                var amount = rows.getColumns().stream().filter(column -> "人民币".equals(column.getText()))
                        .findFirst().orElseThrow();
                for (int index = 0; index < 2; index++) {
                    assertEquals("¥670.00", amount.getCellObservableValue(index).getValue());
                    assertEquals("已核对", rowStatus(workbench, index));
                }
            });
        }
    }

    @Test
    void mileageReviewExplainsUnavailableDistanceAndManualCorrectionKeepsTheOcrEvidence() throws Exception {
        var folder = Files.createDirectory(temporary.resolve("私车公用"));
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, "png", folder.resolve("甲-乙-20261101.png").toFile()));
        image.setRGB(32, 32, 0x4477FF);
        assertTrue(ImageIO.write(image, "png", folder.resolve("甲-乙-20261102.png").toFile()));
        var service = services();
        var info = service.cases.openCase(folder);
        CaseView scanned;
        try (var ticket = service.scan.begin(info.id(), info.revision())) {
            scanned = service.drafts.mergeScan(service.scan.scan(ticket));
        }
        controlledMileageEvidence(service.database, scanned);
        var review = service.drafts.apply(info.id(), scanned.info().revision(), List.of());
        try (var fixture = show(service)) {
            var workbench = fixture.workbench;
            FxTestSession.runOnFxThread(() -> {
                workbench.render(review);
                assertEquals("路线里程待核对", rowStatus(workbench, 0));
                assertEquals("公里数超出精度", rowStatus(workbench, 1));
                assertTrue(hasEvidenceText(workbench, "路线里程待核对，可重扫或补录"));
                assertTrue(hasEvidenceText(workbench, "候选路线里程（公里）"));
                assertEquals("—", label(workbench, "expense-total").getText());
                workbench.save(new EvidenceEditor.Edit(List.of(new DraftService.SetField(
                        review.items().getFirst().id(), "distanceKm", "20")), "确认里程 20 公里"));
            });
            await(() -> !workbench.busy() && "¥18.00".equals(label(workbench, "expense-total").getText()));
            FxTestSession.runOnFxThread(() -> {
                assertEquals("已核对", rowStatus(workbench, 0));
                assertEquals("公里数超出精度", rowStatus(workbench, 1));
                assertTrue(hasEvidenceText(workbench, "路线里程待核对，可重扫或补录"));
                assertTrue(hasEvidenceText(workbench, "候选路线里程（公里）"));
            });
        }
    }

    @Test
    void hotelNightStatusFollowsConfirmedBlankAndRestoredValues() throws Exception {
        Path batch = temporary.resolve("受控批次");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        var service = services();
        var folder = batch.resolve("深圳出差-2026.11.12~13");
        var info = service.cases.openCase(batch, folder);
        CaseView review;
        try (var ticket = service.scan.begin(info.id(), info.revision())) {
            review = service.drafts.mergeScan(service.scan.scan(ticket));
        }
        var hotel = review.items().stream().filter(row -> "HOTEL".equals(row.subtype())).findFirst().orElseThrow();
        int row = review.items().indexOf(hotel);
        try (var fixture = show(service)) {
            var workbench = fixture.workbench;
            FxTestSession.runOnFxThread(() -> {
                workbench.render(review);
                assertEquals("住宿天数待核对", rowStatus(workbench, row));
            });
            saveHotelNights(workbench, new DraftService.SetField(hotel.id(), "nights", "1"));
            assertEquals("1", service.drafts.load(info.id()).items().get(row).fields().get("nights"));
            FxTestSession.runOnFxThread(() -> assertNotEquals("住宿天数待核对", rowStatus(workbench, row)));

            saveHotelNights(workbench, new DraftService.SetField(hotel.id(), "nights", null));
            assertNull(service.drafts.load(info.id()).items().get(row).fields().get("nights"));
            FxTestSession.runOnFxThread(() -> assertNotEquals("住宿天数待核对", rowStatus(workbench, row)));

            saveHotelNights(workbench, new DraftService.RestoreField(hotel.id(), "nights"));
            FxTestSession.runOnFxThread(() -> assertEquals("住宿天数待核对", rowStatus(workbench, row)));
        }
    }

    private static void saveHotelNights(Workbench workbench, DraftService.Change change) throws Exception {
        FxTestSession.runOnFxThread(() -> workbench.save(new EvidenceEditor.Edit(List.of(change), "核对住宿天数")));
        await(() -> !workbench.busy() && "已保存修改".equals(label(workbench, "expense-progress-text").getText()));
    }

    private static void controlledMileageEvidence(Database database, CaseView view) {
        database.transaction(connection -> {
            try (var page = connection.prepareStatement("UPDATE document_page SET kind='MILEAGE', warnings=? WHERE doc_id=?");
                 var field = connection.prepareStatement("""
                         INSERT INTO field_candidate(candidate_id,case_id,doc_id,page_no,key,raw,normalized,method,validation,parser_version)
                             VALUES(?,?,?,1,?,?,?,'OCR','受控 OCR 结果',?)
                         """)) {
                for (int index = 0; index < view.sources().size(); index++) {
                    var doc = view.sources().get(index).docId();
                    boolean selected = index != 0;
                    page.setString(1, "{\"version\":\"" + PageParser.VERSION + "\",\"warnings\":"
                            + (selected ? "[]" : "[\"MILEAGE_NEEDS_REVIEW\"]") + "}");
                    page.setString(2, doc);
                    page.executeUpdate();
                    String value = selected ? "0.1234567890123456" : "12.35";
                    field.setString(1, doc);
                    field.setString(2, view.info().id());
                    field.setString(3, doc);
                    field.setString(4, selected ? "selectedRouteDistanceKm" : "routeDistanceCandidateKm");
                    field.setString(5, value + "公里");
                    field.setString(6, value);
                    field.setString(7, PageParser.VERSION);
                    field.executeUpdate();
                }
            }
            return null;
        });
    }

    private static Object rowStatus(Workbench workbench, int index) {
        var rows = (TableView<?>) workbench.lookup("#expense-rows");
        return rows.getColumns().getLast().getCellObservableValue(index).getValue();
    }

    private static boolean hasEvidenceText(Workbench workbench, String expected) {
        var evidence = (ScrollPane) workbench.lookup("#expense-evidence-values");
        return ((VBox) evidence.getContent()).getChildren().stream()
                .anyMatch(node -> node instanceof Label label && label.getText().contains(expected));
    }

    @Test
    void oldProgressAndStaleEditCannotAdvanceTheDisplayedDraft() throws Exception {
        Path batch = temporary.resolve("受控批次");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        var service = services();
        try (var fixture = show(service)) {
            var workbench = fixture.workbench;
            Path folder = batch.resolve("交通费");
            var existing = service.cases.openCase(batch, folder);
            FxTestSession.runOnFxThread(() -> {
                assertAppVersion(workbench);
                workbench.selectCase(folder);
            });
            await(() -> !workbench.busy() && "¥131.00".equals(label(workbench, "expense-total").getText()));
            var before = service.drafts.load(existing.id());
            var info = before.info();
            assertEquals(List.of("滴滴电子发票-代驾-01.pdf", "行程报销单-代驾-01.pdf"),
                    before.sources().stream().map(source -> source.relativePath()).sorted().toList());
            assertPreview(workbench, before, "行程报销单-代驾-01.pdf", CropMode.TRIP_STATEMENT);
            assertPreview(workbench, before, "滴滴电子发票-代驾-01.pdf", CropMode.NONE);
            var item = before.items().getFirst();
            var newer = service.drafts.apply(info.id(), info.revision(), List.of(new DraftService.SetField(item.id(), "note", "另一个窗口")));
            FxTestSession.runOnFxThread(() -> workbench.save(new EvidenceEditor.Edit(
                    List.of(new DraftService.SetField(item.id(), "note", "未提交")), "备注 · 未提交")));
            await(() -> !workbench.busy() && button(workbench, "expense-compare-edit").isVisible());
            FxTestSession.runOnFxThread(() -> {
                assertAppVersion(workbench);
                assertEquals("已重新加载 · 当前修订 " + newer.info().revision(),
                        label(workbench, "expense-progress-text").getText());
            });
            assertEquals(newer.info().revision(), service.drafts.load(info.id()).info().revision());
            assertEquals("另一个窗口", service.drafts.load(info.id()).items().getFirst().fields().get("note"));

            Path empty = Files.createDirectory(batch.resolve("空目录"));
            FxTestSession.runOnFxThread(() -> {
                fixture.stage.setWidth(620);
                workbench.selectCase(empty);
                workbench.handleProgress(new ScanService.Progress(info.id(), "old-job", "RECOGNIZE", 1, 1, "迟到凭证.pdf"));
                assertFalse(label(workbench, "expense-progress-text").getText().contains("迟到凭证"));
            });
            await(() -> !workbench.busy() && label(workbench, "expense-case-path").getText().equals("空目录"));
            await(() -> ((SplitPane) workbench.lookup("#expense-review")).getOrientation() == javafx.geometry.Orientation.VERTICAL);
            FxTestSession.runOnFxThread(() -> {
                assertAppVersion(workbench);
                workbench.handleProgress(new ScanService.Progress(info.id(), "old-job", "RECOGNIZE", 1, 1, "迟到凭证.pdf"));
                assertFalse(label(workbench, "expense-progress-text").getText().contains("迟到凭证"));
                assertTrue(button(workbench, "expense-generate").isDisabled());
                assertFalse(button(workbench, "expense-compare-edit").isVisible());
                assertEquals("—", label(workbench, "expense-total").getText());
                assertEquals("扫描结束，目录中没有可识别凭证。", label(workbench, "expense-progress-text").getText());
            });
        }
    }

    private static void assertPreview(Workbench workbench, CaseView view, String file, CropMode mode)
            throws Exception {
        var attachment = view.attachments().stream().filter(page -> page.relativePath().equals(file)).findFirst().orElseThrow();
        assertEquals(mode, attachment.cropMode());
        Path path = view.info().folder().resolve(file);
        var expected = AttachmentImages.render(path, attachment.sourcePage(), 105, mode);
        if (mode == CropMode.TRIP_STATEMENT) {
            assertTrue(expected.getHeight() < PageImages.render(path, attachment.sourcePage(), 105).getHeight());
        }
        FxTestSession.runOnFxThread(() -> selectEvidence((ComboBox<?>) workbench.lookup("#expense-document"), file));
        await(() -> {
            var image = ((ImageView) workbench.lookup("#expense-preview")).getImage();
            return image != null && image.getWidth() == expected.getWidth() && image.getHeight() == expected.getHeight();
        });
    }

    private static <T> void selectEvidence(ComboBox<T> box, String path) {
        var entry = box.getItems().stream().filter(item -> box.getConverter().toString(item).contains(path))
                .findFirst().orElseThrow();
        box.getSelectionModel().select(entry);
    }

    private Services services() {
        Path data = temporary.resolve("app data");
        var app = new AppPaths(temporary, data, temporary.resolve("app cache"), temporary.resolve("test outputs"));
        var database = Database.open(app.databaseFile());
        var jobs = new CaseJobs();
        var policy = new PathPolicy(database);
        var cases = new CaseService(database, policy);
        var scan = new ScanService(cases, policy, jobs);
        var drafts = new DraftService(database, jobs, RecognitionService.fromPaths(app));
        var archive = new TemplateArchive(data);
        archive.installBundled();
        var templates = new TemplateCatalog(archive);
        var exports = new ExportService(database, jobs, policy, templates, app);
        return new Services(database, cases, scan, drafts, archive, templates, exports);
    }

    private static Ui show(Services service) throws Exception {
        return FxTestSession.runOnFxThread(() -> {
            var stage = new Stage();
            var workbench = new Workbench(service.cases, service.scan, service.drafts,
                    new WpsLauncher(), service.exports, service.archive, service.templates);
            stage.setScene(new Scene(workbench, 1050, 740));
            stage.show();
            return new Ui(stage, workbench);
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long stop = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (System.nanoTime() < stop) {
            if (FxTestSession.runOnFxThread(condition::getAsBoolean)) return;
            Thread.sleep(55);
        }
        fail("JavaFX control did not reach the expected state");
    }

    private static Button button(Workbench workbench, String id) { return (Button) workbench.lookup("#" + id); }
    private static Label label(Workbench workbench, String id) { return (Label) workbench.lookup("#" + id); }

    private static void assertAppVersion(Workbench workbench) {
        assertEquals("版本 " + AppVersion.value().orElseThrow(), label(workbench, "expense-app-version").getText());
        assertNull(workbench.lookup("#expense-revision"));
    }

    private record Services(Database database, CaseService cases, ScanService scan, DraftService drafts,
                            TemplateArchive archive, TemplateCatalog templates, ExportService exports) {}

    private record Ui(Stage stage, Workbench workbench) implements AutoCloseable {
        @Override public void close() throws Exception {
            FxTestSession.runOnFxThread(() -> {
                stage.close();
                workbench.close();
            });
        }
    }
}
