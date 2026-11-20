package expenseassistant.draft;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import expenseassistant.casework.CaseException;
import expenseassistant.casework.CaseJobs;
import expenseassistant.casework.CaseService;
import expenseassistant.draft.DraftService.LinkEvidence;
import expenseassistant.draft.DraftService.SetAmount;
import expenseassistant.draft.DraftService.SetAttachment;
import expenseassistant.draft.DraftService.SetCaption;
import expenseassistant.draft.DraftService.SetField;
import expenseassistant.intake.PathPolicy;
import expenseassistant.intake.ScanService;
import expenseassistant.matching.ExpenseAmounts.Component;
import expenseassistant.matching.ExpenseMatcher.Item;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.output.ExportSnapshot;
import expenseassistant.output.ExportSnapshot.Layout;
import expenseassistant.platform.AppPaths;
import expenseassistant.recognition.PageParser;
import expenseassistant.recognition.PageResult;
import expenseassistant.recognition.RecognitionService;
import expenseassistant.store.Database;
import expenseassistant.support.ReceiptFixtures;
import expenseassistant.support.SampleCorpus;

import static org.junit.jupiter.api.Assertions.*;

class DraftServiceTest {
    @TempDir Path temporary;

    @Test
    void tollInvoicesReconcileExactlyUntilAnInputIsMissing() throws Exception {
        var services = setup();
        var folder = services.batch().resolve("杭州出差-2026.11.03");
        var info = services.cases().openCase(services.batch(), folder);
        CaseView first = services.scan(info.id(), info.revision());
        assertEquals(List.of("TOLL", "PARKING"), first.items().stream().map(item -> item.subtype()).toList());
        assertEquals(15879L, first.confirmedTotal());
        assertEquals(List.of(12879L, 3000L), first.items().stream()
                .map(item -> item.amounts().get(Component.TRANSPORT).confirmedMinor()).toList());
        assertEquals(6, first.claims().size());
        assertEquals(8, first.attachments().size());
        assertTrue(first.attachments().subList(0, 5).stream().allMatch(page -> page.role() == Role.CHARGE
                && page.relativePath().startsWith("通行费电子发票/")));
        assertTrue(first.attachments().subList(5, 7).stream().allMatch(page -> page.role() == Role.SUPPORT));
        assertTrue(first.attachments().getLast().relativePath().startsWith("杭州停车费"));
        assertEquals(first.info().revision(), services.scan(info.id(), first.info().revision()).info().revision());

        var receipt = first.sources().stream().filter(source -> source.relativePath().startsWith("杭州停车费")).findFirst().orElseThrow();
        var edited = services.drafts().apply(info.id(), first.info().revision(), List.of(new SetCaption(receipt.docId(), 1, "")));
        assertEquals(first.info().revision() + 1, edited.info().revision());
        assertEquals("", edited.attachments().getLast().caption());
        assertEquals(edited.info().revision(), services.drafts().apply(info.id(), edited.info().revision(),
                List.of(new SetCaption(receipt.docId(), 1, ""))).info().revision());
        assertEquals("STALE_REVISION", assertThrows(CaseException.class,
                () -> services.drafts().apply(info.id(), first.info().revision(), List.of(new SetCaption(receipt.docId(), 1, "旧编辑")))).code());

        Files.delete(folder.resolve(first.attachments().getFirst().relativePath()));
        var missing = services.scan(info.id(), edited.info().revision());
        assertEquals(3000L, missing.confirmedTotal());
        assertTrue(missing.items().getFirst().reviewReasons().contains("TOLL_RECONCILIATION_REQUIRED"));
        assertEquals(1, missing.claims().size());
        var reopened = new DraftService(Database.open(services.database().file()), new CaseJobs(), services.recognition());
        assertEquals(missing, reopened.load(info.id()));
    }

    @Test
    void taxiInvoiceAndTripChargeOnceAndPreserveManualFieldAfterRename() throws Exception {
        var services = setup();
        var folder = services.batch().resolve("交通费");
        var info = services.cases().openCase(services.batch(), folder);
        var first = services.scan(info.id(), 0);
        var item = first.items().getFirst();
        assertEquals(13100L, first.confirmedTotal());
        assertEquals(LocalDate.parse("2026-10-28"), item.startDate());
        assertEquals(LocalDate.parse("2026-10-29"), item.endDate());
        assertEquals(2, first.attachments().size());
        assertEquals(1, first.claims().size());

        var edited = services.drafts().apply(info.id(), first.info().revision(),
                List.of(new SetField(item.id(), "description", "代驾核对"), new SetField(item.id(), "startDate", null)));
        assertNull(edited.items().getFirst().startDate());
        assertEquals(13100L, edited.confirmedTotal());
        assertEquals(edited.info().revision(), services.scan(info.id(), edited.info().revision()).info().revision());

        var original = first.sources().stream().filter(source -> source.relativePath().startsWith("滴滴电子发票")).findFirst().orElseThrow();
        Files.move(folder.resolve(original.relativePath()), folder.resolve("移动-" + original.relativePath()));
        var renamed = services.scan(info.id(), edited.info().revision());
        assertEquals(item.id(), renamed.items().getFirst().id());
        assertEquals("代驾核对", renamed.items().getFirst().description());
        assertEquals(13100L, renamed.confirmedTotal());

        var savedMinor = services.drafts().apply(info.id(), renamed.info().revision(),
                List.of(new SetAmount(item.id(), Component.TAXI, "CNY", 12345L, "人工核定")));
        assertEquals(12345L, savedMinor.confirmedTotal());
        assertEquals(renamed.info().revision() + 1, savedMinor.info().revision());
        var rollback = assertThrows(CaseException.class, () -> services.drafts().apply(info.id(), savedMinor.info().revision(),
                List.of(new SetField(item.id(), "description", "应回滚"),
                        new SetAmount(item.id(), Component.TAXI, "USD", 12L, "币种不适用"))));
        assertEquals("INVALID_CHANGE", rollback.code());
        assertEquals(savedMinor, services.drafts().load(info.id()));
    }

    @Test
    void duplicateCanBeAppendedAsSupportWithoutAnotherCharge() throws Exception {
        var services = setup();
        var folder = services.batch().resolve("交通费");
        Path original = folder.resolve("滴滴电子发票-代驾-01.pdf");
        Path copy = folder.resolve("同票副本.pdf");
        Files.copy(original, copy);
        Files.writeString(copy, "\n% reproduced version\n", java.nio.file.StandardOpenOption.APPEND);
        var info = services.cases().openCase(services.batch(), folder);
        var draft = services.scan(info.id(), 0);
        assertEquals(13100L, draft.confirmedTotal());
        assertEquals(2, draft.attachments().size());
        assertEquals(1, draft.duplicateUnitIds().size());
        var duplicate = draft.duplicateUnitIds().iterator().next();
        var edition = new LinkEvidence(draft.items().getFirst().id(), duplicate, Role.SUPPORT, null, false);
        var appended = services.drafts().apply(info.id(), draft.info().revision(), List.of(edition));
        assertEquals(3, appended.attachments().size());
        assertEquals(13100L, appended.confirmedTotal());
        assertEquals(1, appended.claims().size());
        assertEquals(appended.info().revision(), services.drafts().apply(info.id(), appended.info().revision(),
                List.of(edition)).info().revision());
    }

    @Test
    void excludingChargeSourceWithdrawsAmountAndPersistsPrintSettings() throws Exception {
        var services = setup();
        var info = services.cases().openCase(services.batch(), services.batch().resolve("杭州出差-2026.11.03"));
        var first = services.scan(info.id(), 0);
        var parking = first.attachments().getLast();
        var after = services.drafts().apply(info.id(), first.info().revision(),
                List.of(new SetAttachment(parking.docId(), 1, 1, "实际未报销", Layout.TWO_PER_PAGE, null, "费用已撤销")));
        assertEquals(12879L, after.confirmedTotal());
        assertEquals(7, after.attachments().size());
        assertEquals(7, ExportSnapshot.fromDraft(after).pages().size());
        assertEquals(after.info().revision(), services.scan(info.id(), after.info().revision()).info().revision());
    }

    @Test
    void appleAmountsConvertAutomaticallyAndReplacementRemovalRevokesTheManualAmount() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("expenseassistant.ocrRoot") != null,
                "需使用经校验的本机 OCR 组件");
        var services = setup();
        var folder = services.batch().resolve("其他报销");
        var info = services.cases().openCase(services.batch(), folder);
        var onlyReceipts = services.scan(info.id(), 0);
        assertEquals(List.of(LocalDate.parse("2026-09-17"), LocalDate.parse("2026-11-17")),
                onlyReceipts.items().stream().map(item -> item.startDate()).toList());
        assertEquals(134000L, onlyReceipts.confirmedTotal());
        onlyReceipts.items().forEach(item -> {
            var amount = item.amounts().get(Component.OTHER);
            assertEquals(67000L, amount.confirmedMinor());
            assertEquals("100.00", amount.faceValue());
            assertEquals("USD", amount.originalCurrency());
        });

        Path parking = services.batch().resolve("杭州出差-2026.11.03/杭州停车费30元.pdf");
        Files.copy(parking, folder.resolve("替票.pdf"));
        var draft = services.scan(info.id(), onlyReceipts.info().revision());
        var receipt = draft.items().getFirst();
        var replacement = draft.sources().stream().filter(source -> source.relativePath().equals("替票.pdf"))
                .findFirst().orElseThrow().units().getFirst();
        var revision = draft.info().revision();
        assertEquals("CHARGE_ALREADY_USED", assertThrows(CaseException.class, () -> services.drafts().apply(info.id(),
                revision, List.of(new LinkEvidence(receipt.id(), replacement.unitId(), Role.REPLACEMENT,
                        Component.OTHER, false)))).code());
        var withReplacement = services.drafts().apply(info.id(), revision, List.of(
                new LinkEvidence(receipt.id(), replacement.unitId(), Role.REPLACEMENT, Component.OTHER, true),
                new SetAmount(receipt.id(), Component.OTHER, "CNY", 12345L, "确认人民币金额")));
        assertEquals(79345L, withReplacement.confirmedTotal());
        assertEquals(1, withReplacement.claims().size());
        assertEquals(receipt.id(), withReplacement.claims().getFirst().itemId());
        var detached = services.drafts().apply(info.id(), withReplacement.info().revision(),
                List.of(new LinkEvidence(receipt.id(), replacement.unitId(), null, null, false)));
        var reopenedReceipt = detached.items().stream().filter(item -> item.id().equals(receipt.id())).findFirst().orElseThrow();
        assertNull(reopenedReceipt.amounts().get(Component.OTHER).confirmedMinor());
        assertTrue(reopenedReceipt.reviewReasons().contains("SOURCE_REVIEW_REQUIRED"));
        assertEquals(70000L, detached.confirmedTotal());
        assertTrue(detached.overrides().stream().anyMatch(row -> row.targetId().equals(receipt.id())
                && row.fieldKey().equals("AMOUNT:OTHER") && row.sourceHashes().size() == 2));
    }

    @Test
    void manualUsdInvoiceLinksKeepEachConversionInTheFrozenAudit() throws Exception {
        var services = setup();
        var controlled = Files.createDirectories(temporary.resolve("受控批次/其他报销"));
        var fixtures = SampleCorpus.verify(services.batch());
        for (int index = 0; index < 2; index++) {
            var fixture = fixtures.stream().filter(entry -> "INVOICE".equals(entry.kind())).toList().get(index);
            Files.copy(services.batch().resolve(fixture.relativePath()), controlled.resolve("发票" + index + ".pdf"));
        }
        ReceiptFixtures.receipt(controlled.resolve("收据.pdf"), "Example Shop", "REC-001", "$100.00");
        var info = services.cases().openCase(controlled);
        var view = services.scan(info.id(), info.revision());
        var invoices = view.sources().stream().filter(source -> source.relativePath().startsWith("发票")).toList();
        for (int index = 0; index < invoices.size(); index++) {
            replaceWithUsdInvoice(services, view, invoices.get(index), index == 0 ? "100.00" : "101.00");
        }
        view = edit(services, view);
        assertEquals(134670L, view.confirmedTotal());
        String target = view.items().stream().filter(item -> "RECEIPT".equals(item.subtype())).findFirst().orElseThrow().id();
        var changes = invoices.stream().map(source -> new LinkEvidence(target,
                source.units().getFirst().unitId(), Role.CHARGE, Component.OTHER, true)).toArray(DraftService.Change[]::new);
        view = edit(services, view, changes);
        assertEquals(134670L, view.confirmedTotal());
        var billed = view.items().stream().filter(item -> item.id().equals(target)).findFirst().orElseThrow();
        assertEquals(134670L, billed.amounts().get(Component.OTHER).confirmedMinor());
        assertFalse(billed.reviewReasons().contains("AMOUNT_NEEDS_REVIEW"));
        assertFalse(billed.reviewReasons().contains("CNY_CONFIRMATION_REQUIRED"));
        assertEquals(List.of(67000L, 67670L), view.claims().stream().map(claim -> claim.minor()).sorted().toList());
        assertEquals(List.of("USD 100.00 × 6.7", "USD 101.00 × 6.7"), ExportSnapshot.fromDraft(view).audit()
                .evidence().stream().filter(link -> target.equals(link.itemId()) && link.role() == Role.CHARGE)
                .map(ExportSnapshot.EvidenceBasis::basis).sorted().toList());
    }

    @Test
    void aChangedUsdReceiptWaitsForSourceReviewBeforeContributingItsNewAmount() throws Exception {
        var services = setup();
        var folder = Files.createDirectories(temporary.resolve("受控批次/其他报销"));
        var receipt = folder.resolve("收据.pdf");
        ReceiptFixtures.receipt(receipt, "Example Shop", "REC-001", "USD 100.00");
        var info = services.cases().openCase(folder);
        var first = services.scan(info.id(), info.revision());
        ReceiptFixtures.receipt(receipt, "Example Shop", "REC-001", "USD 101.00");
        var changed = services.scan(info.id(), first.info().revision());
        assertEquals(first.items().getFirst().id(), changed.items().getFirst().id());
        assertNull(changed.confirmedTotal());
        assertTrue(changed.items().getFirst().reviewReasons().contains("SOURCE_REVIEW_REQUIRED"));
        var live = changed.sources().stream().filter(source -> !source.missing()).findFirst().orElseThrow();
        var accepted = edit(services, changed, new DraftService.AcceptChangedSource(live.docId()));
        assertEquals(67670L, accepted.confirmedTotal());
    }

    @Test
    void openingAnOldUsdDraftRefreshesAutomaticAmountsAndKeepsManualOverrides() throws Exception {
        var services = setup();
        var draft = receiptDraft(services, "USD 100.00", "USD 100.00", "USD 100.00", "USD 100.00");
        var automatic = draft.items().get(0).id();
        var confirmed = draft.items().get(1).id();
        var blank = draft.items().get(2).id();
        var invalid = draft.items().get(3).id();
        var edited = edit(services, draft, new SetAmount(confirmed, Component.OTHER, "CNY", 12345L, "人工核定"),
                new SetAmount(blank, Component.OTHER, "CNY", null, "人工留空"),
                new SetAmount(invalid, Component.OTHER, "CNY", 22200L, "旧替票核定"));
        String id = draft.info().id();
        services.database().transaction(connection -> {
            var store = new DraftStore(connection, id);
            store.update("UPDATE reimbursement_case SET rule_version='1' WHERE id=?", id);
            store.update("UPDATE document_page SET warnings=json_set(warnings,'$.version','3') WHERE case_id=?", id);
            store.update("UPDATE field_candidate SET parser_version='3' WHERE case_id=?", id);
            store.update("UPDATE expense_amount SET confirmed_cny_minor=NULL,basis=? WHERE item_id=?",
                    "原币收据待核定人民币金额", automatic);
            var source = edited.sources().getLast();
            store.update("UPDATE field_override SET source_hashes=? WHERE case_id=? AND target_id=? AND field_key='AMOUNT:OTHER'",
                    DraftStore.encode(List.of(source.sha256(), "0".repeat(64))), id, invalid);
            return null;
        });
        var old = services.drafts().load(id);
        assertEquals(34545L, old.confirmedTotal());
        assertEquals("AMOUNT_RULE_REFRESH_REQUIRED", DraftService.pendingAmountRefreshCode(old));
        var updated = services.drafts().loadForReview(id);
        assertEquals(old.info().revision() + 1, updated.info().revision());
        assertEquals("3", updated.info().ruleVersion());
        assertEquals(old.info().scanGeneration(), updated.info().scanGeneration());
        assertEquals(old.sources(), updated.sources());
        assertEquals(old.overrides(), updated.overrides());
        assertEquals(67000L, item(updated, automatic).amounts().get(Component.OTHER).confirmedMinor());
        assertEquals(12345L, item(updated, confirmed).amounts().get(Component.OTHER).confirmedMinor());
        assertNull(item(updated, blank).amounts().get(Component.OTHER).confirmedMinor());
        assertNull(item(updated, invalid).amounts().get(Component.OTHER).confirmedMinor());
        assertTrue(item(updated, invalid).reviewReasons().contains("SOURCE_REVIEW_REQUIRED"));
        assertEquals(79345L, updated.confirmedTotal());
        assertEquals(updated, services.drafts().loadForReview(id));
        var rescanned = services.scan(id, updated.info().revision());
        assertTrue(rescanned.sources().stream().flatMap(source -> source.pages().stream())
                .allMatch(page -> "4".equals(page.parserVersion()) && "USD".equals(page.value("currency"))));
        assertEquals(updated.confirmedTotal(), rescanned.confirmedTotal());
    }

    @Test
    void openingAnOldDraftWithOnlyAmbiguousDollarsDoesNotCreateARevision() throws Exception {
        var services = setup();
        var current = receiptDraft(services, "$100.00");
        var legacy = setRuleVersion(services, current, "1");
        assertNull(legacy.confirmedTotal());
        assertNull(DraftService.pendingAmountRefreshCode(legacy));
        assertEquals(legacy, services.drafts().loadForReview(legacy.info().id()));
        assertEquals(legacy, services.drafts().loadForReview(legacy.info().id()));
        assertEquals("1", ExportSnapshot.fromDraft(legacy).ruleVersion());
    }

    @Test
    void openingAnOldDraftWithEquivalentUsdProjectionDoesNotCreateARevision() throws Exception {
        var services = setup();
        var current = receiptDraft(services, "USD 100.00");
        var legacy = setRuleVersion(services, current, "2");
        assertEquals(67000L, legacy.confirmedTotal());
        assertNull(DraftService.pendingAmountRefreshCode(legacy));
        assertEquals(legacy, services.drafts().loadForReview(legacy.info().id()));
        var source = legacy.sources().getFirst();
        var excluded = edit(services, legacy,
                new SetAttachment(source.docId(), 1, 1, null, Layout.TWO_PER_PAGE, null, "不参与报销"));
        var legacyExcluded = setRuleVersion(services, excluded, "1");
        assertNotNull(source.units().getFirst().usdMinor());
        assertNull(legacyExcluded.confirmedTotal());
        assertNull(DraftService.pendingAmountRefreshCode(legacyExcluded));
        assertEquals(legacyExcluded, services.drafts().loadForReview(legacyExcluded.info().id()));
    }

    private CaseView receiptDraft(Services services, String... amounts) throws IOException {
        var folder = Files.createDirectories(temporary.resolve("受控旧版批次/其他报销"));
        for (int index = 0; index < amounts.length; index++) {
            ReceiptFixtures.receipt(folder.resolve(index + ".pdf"), "Shop", "REC-00" + index, amounts[index]);
        }
        var info = services.cases().openCase(folder);
        return services.scan(info.id(), info.revision());
    }

    private static CaseView setRuleVersion(Services services, CaseView current, String rule) {
        String id = current.info().id();
        services.database().transaction(connection -> {
            new DraftStore(connection, id).update("UPDATE reimbursement_case SET rule_version=? WHERE id=?", rule, id);
            return null;
        });
        return services.drafts().load(id);
    }

    @Test
    void reparsingAnOldReceiptKeepsItsExistingManualAmountOrExplicitBlank() throws Exception {
        var services = setup();
        var folder = Files.createDirectories(temporary.resolve("受控批次/其他报销"));
        ReceiptFixtures.receipt(folder.resolve("1.pdf"), "Shop", "REC-001", "USD 100.00");
        ReceiptFixtures.receipt(folder.resolve("2.pdf"), "Shop", "REC-002", "USD 100.00");
        var info = services.cases().openCase(folder);
        var first = services.scan(info.id(), info.revision());
        var legacy = downgradeReceipts(services, first);
        String confirmed = legacy.items().getFirst().id();
        String blank = legacy.items().getLast().id();
        var manuallyEdited = edit(services, legacy,
                new SetAmount(confirmed, Component.OTHER, "CNY", 12345L, "人工核定"),
                new SetAmount(blank, Component.OTHER, "CNY", null, "人工留空"));
        var reparsed = services.scan(info.id(), manuallyEdited.info().revision());
        assertEquals(2, reparsed.items().size());
        assertEquals(12345L, reparsed.confirmedTotal());
        assertEquals(12345L, item(reparsed, confirmed).amounts().get(Component.OTHER).confirmedMinor());
        assertNull(item(reparsed, blank).amounts().get(Component.OTHER).confirmedMinor());
        assertTrue(reparsed.sources().stream().flatMap(source -> source.pages().stream())
                .allMatch(page -> PageParser.VERSION.equals(page.parserVersion()) && "USD".equals(page.value("currency"))));
        var restored = edit(services, reparsed, new DraftService.RestoreField(blank, "AMOUNT:OTHER"));
        assertEquals(79345L, restored.confirmedTotal());
    }

    @Test
    void aNewlyRecognizedDuplicatePrefersTheOneManualReceiptAndKeepsItsSource() throws Exception {
        var services = setup();
        var legacy = legacyReceiptCopies(services);
        String preferred = legacy.items().getLast().id();
        var edited = edit(services, legacy, new SetAmount(preferred, Component.OTHER, "CNY", 24001L, "人工核定第二份"));
        var reparsed = services.scan(edited.info().id(), edited.info().revision());
        assertEquals(1, reparsed.items().size());
        assertEquals(preferred, reparsed.items().getFirst().id());
        assertEquals(24001L, reparsed.confirmedTotal());
        assertEquals(List.of("2.pdf"), reparsed.attachments().stream().map(page -> page.relativePath()).toList());
        assertEquals(reparsed, services.drafts().load(reparsed.info().id()));
        assertEquals(reparsed.info().revision(), services.scan(reparsed.info().id(), reparsed.info().revision()).info().revision());
    }

    @Test
    void aNewlyRecognizedDuplicateWithOldManualConflictsKeepsBothSourcesForReview() throws Exception {
        var services = setup();
        var legacy = legacyReceiptCopies(services);
        var edited = edit(services, legacy,
                new SetAmount(legacy.items().getFirst().id(), Component.OTHER, "CNY", 24001L, "人工核定第一份"),
                new SetAmount(legacy.items().getLast().id(), Component.OTHER, "CNY", null, "人工留空第二份"));
        var reparsed = services.scan(edited.info().id(), edited.info().revision());
        assertEquals(1, reparsed.items().size());
        assertNull(reparsed.confirmedTotal());
        assertTrue(reparsed.items().getFirst().reviewReasons().contains("RECEIPT_CONFLICT"));
        assertEquals(List.of(Role.PENDING, Role.PENDING), reparsed.links().stream().map(link -> link.role()).toList());
        assertEquals(2, reparsed.attachments().size());
        assertEquals(reparsed.info().revision(), services.scan(reparsed.info().id(), reparsed.info().revision()).info().revision());
        var resolved = edit(services, reparsed,
                new SetAmount(reparsed.items().getFirst().id(), Component.OTHER, "CNY", 23001L, "合并后人工核定"));
        assertEquals(23001L, resolved.confirmedTotal());
        assertEquals(2, resolved.attachments().size());
        assertEquals(resolved.info().revision(), services.scan(resolved.info().id(), resolved.info().revision()).info().revision());
    }

    private CaseView legacyReceiptCopies(Services services) throws Exception {
        var folder = Files.createDirectories(temporary.resolve("受控批次/其他报销"));
        ReceiptFixtures.receipt(folder.resolve("1.pdf"), "Shop", "REC-001", "USD 100.00");
        ReceiptFixtures.writePdf(folder.resolve("2.pdf"), """
                Receipt
                Merchant: Shop
                Receipt No: REC-001
                Date: 2026-11-20
                Total: USD 100.00
                COPY
                """);
        var info = services.cases().openCase(folder);
        return downgradeReceipts(services, services.scan(info.id(), info.revision()));
    }

    private static CaseView downgradeReceipts(Services services, CaseView view) {
        services.database().transaction(connection -> {
            var store = new DraftStore(connection, view.info().id());
            for (var source : view.sources()) {
                String path = source.relativePath();
                var legacy = new PageResult(1, PageResult.Kind.RECEIPT, true, List.of(), List.of(), List.of(), List.of(), "3");
                store.putSource(new ScanService.SourceFile(view.info().folder().resolve(path), path, path, source.sha256(),
                        ScanService.Format.PDF), List.of(legacy));
            }
            return null;
        });
        return edit(services, view);
    }

    private static void replaceWithUsdInvoice(Services services, CaseView view, CaseView.Source source, String gross) {
        var before = source.pages().getFirst();
        var fields = new java.util.ArrayList<>(before.fields());
        fields.removeIf(field -> List.of("gross", "currency").contains(field.key()));
        fields.add(new PageResult.FieldCandidate("gross", gross, gross, PageResult.Method.PDF_TEXT, null, "受控票面总额"));
        fields.add(new PageResult.FieldCandidate("currency", "USD", "USD", PageResult.Method.PDF_TEXT, null, "受控票面币种"));
        var page = new PageResult(1, before.kind(), true, fields, before.segments(), before.summaryReferences(),
                List.of(), PageParser.VERSION);
        services.database().transaction(connection -> {
            var store = new DraftStore(connection, view.info().id());
            String path = source.relativePath();
            store.putSource(new ScanService.SourceFile(view.info().folder().resolve(path), path, path, source.sha256(),
                    ScanService.Format.PDF), List.of(page));
            return null;
        });
    }

    @Test
    void supplementalPrivateCarScreenshotsUseTheSelectedRoutes() throws Exception {
        String path = System.getProperty("expenseassistant.privateCarSampleRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && System.getProperty("expenseassistant.ocrRoot") != null,
                "需指定实图目录及 OCR 组件");
        var services = setup();
        var folder = Files.createDirectory(services.batch().resolve("私车公用"));
        try (var files = Files.list(Path.of(path))) {
            for (Path file : files.filter(candidate -> candidate.toString().endsWith(".jpg")).toList()) {
                Files.copy(file, folder.resolve(file.getFileName()));
            }
        }
        var info = services.cases().openCase(services.batch(), folder);
        var view = services.scan(info.id(), 0);
        assertEquals(2, view.attachments().size());
        assertEquals(List.of(LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-18")),
                view.items().stream().map(item -> item.startDate()).toList());
        assertMileage(view.items().getFirst(), "167", 15_030L);
        assertMileage(view.items().getLast(), "69", 6_210L);
        assertEquals(List.of("167", "69"), view.items().stream().map(row -> row.fields().get("rawDistanceKm")).toList());
        assertTrue(view.items().stream().allMatch(row -> !row.fields().containsKey("direction")));
        assertTrue(view.attachments().stream().allMatch(page -> page.role() == Role.SUPPORT && page.sourcePage() == 1));
        assertEquals(21_240L, view.confirmedTotal());
    }

    @Test
    void mileageDirectionsAndOverridesRecalculateTheRoundedFuel() throws Exception {
        var services = setup();
        var view = scanMileage(services, "甲-乙-20261101.png", "甲-乙-单程-20261102.png", "甲-乙-往返-20261103.png");
        record Expected(String km, long minor) {}
        var expect = List.of(new Expected("12.35", 1112), new Expected("12.35", 1112), new Expected("24.70", 2223));
        for (int index = 0; index < expect.size(); index++) {
            String path = view.sources().get(index).relativePath();
            replaceMileagePage(services, view, path, "selectedRouteDistanceKm", "12.35", index == 0 ? "3" : PageParser.VERSION);
        }
        view = edit(services, view);
        for (int index = 0; index < expect.size(); index++) {
            assertMileage(view.items().get(index), expect.get(index).km(), expect.get(index).minor());
        }
        String row = view.items().getLast().id();
        view = edit(services, view, new SetField(row, "direction", null));
        assertMileage(view.items().getLast(), "12.35", 1112L);
        view = edit(services, view, new SetField(row, "distanceKm", "20"));
        assertMileage(view.items().getLast(), "20", 1800L);
        assertTrue(view.items().getLast().amounts().get(Component.FUEL).basis().startsWith("人工确认里程"));
        view = edit(services, view, new SetField(row, "distanceKm", null));
        assertMileage(view.items().getLast(), null, null);
        assertTrue(view.items().getLast().reviewReasons().isEmpty());
        view = edit(services, view, new DraftService.RestoreField(row, "distanceKm"));
        assertMileage(view.items().getLast(), "12.35", 1112L);
        assertTrue(view.items().getLast().amounts().get(Component.FUEL).basis().startsWith("选中路线"));
        view = edit(services, view, new SetField(row, "distanceKm", "0"));
        assertMileage(view.items().getLast(), "0", 0L);
    }

    @Test
    void invalidOcrDistanceIsIsolatedAndManualPrecisionFailuresRollBack() throws Exception {
        var services = setup();
        var view = scanMileage(services, "甲-乙-20261101.png", "甲-乙-20261102.png");
        replaceMileagePage(services, view, "甲-乙-20261101.png", "selectedRouteDistanceKm", "0.612345678901234",
                PageParser.VERSION);
        replaceMileagePage(services, view, "甲-乙-20261102.png", "selectedRouteDistanceKm", "0.1234567890123456",
                PageParser.VERSION);
        view = edit(services, view);
        assertMileage(view.items().getFirst(), "0.612345678901234", 55L);
        assertMileage(view.items().getLast(), null, null);
        assertTrue(view.items().getLast().reviewReasons().contains("MILEAGE_OUT_OF_RANGE"));
        String row = view.items().getFirst().id();
        rejectMileageEdit(services, view, new SetField(row, "direction", "往返"));
        view = edit(services, view, new SetField(row, "distanceKm", "0.123456789012345"));
        assertMileage(view.items().getFirst(), "0.123456789012345", 11L);
        for (String invalid : List.of("0.1234567890123456", "102481911520608621", "-1", "无效", "1E-309")) {
            rejectMileageEdit(services, view, new SetField(row, "distanceKm", invalid));
        }
        view = edit(services, view, new SetField(view.items().getLast().id(), "distanceKm", "20"));
        assertMileage(view.items().getLast(), "20", 1800L);
        assertTrue(view.items().getLast().reviewReasons().isEmpty());
    }

    @Test
    void oldMileageCandidatesAndFuelOverridesStayOutOfTheEffectiveAmount() throws Exception {
        var services = setup();
        var view = scanMileage(services, "甲-乙-往返-20261101.png");
        var info = view.info();
        try (var files = Files.list(services.batch().resolve("杭州出差-2026.11.03"))) {
            var parking = files.filter(file -> file.getFileName().toString().contains("停车费")).findFirst().orElseThrow();
            Files.copy(parking, info.folder().resolve(parking.getFileName()));
        }
        view = services.scan(info.id(), info.revision());
        replaceMileagePage(services, view, "甲-乙-往返-20261101.png", "distanceKm", "12.35", "2");
        var mileage = view.items().stream().filter(item -> "MILEAGE".equals(item.subtype())).findFirst().orElseThrow();
        var screenshot = view.sources().stream().filter(source -> source.relativePath().endsWith(".png")).findFirst().orElseThrow();
        var parking = view.sources().stream().filter(source -> source.relativePath().endsWith(".pdf")).findFirst().orElseThrow();
        var fuel = new CaseView.OverrideView(mileage.id(), "AMOUNT:FUEL", "SET",
                DraftStore.encode(new DraftProjection.ConfirmedAmount(9900L, "旧版人工核定")),
                List.of(screenshot.sha256()), view.info().revision());
        var link = new CaseView.OverrideView(mileage.id(), "LINK:" + parking.units().getFirst().unitId(), "SET",
                DraftStore.encode(new DraftProjection.ManualLink(Role.CHARGE, Component.FUEL, true)),
                List.of(parking.sha256()), view.info().revision());
        var raw = new CaseView.OverrideView(mileage.id(), "rawDistanceKm", "SET", "7",
                List.of(), view.info().revision());
        services.database().transaction(connection -> {
            var store = new DraftStore(connection, info.id());
            store.putOverride(fuel);
            store.putOverride(link);
            store.putOverride(raw);
            return null;
        });
        view = edit(services, view);
        assertMileage(item(view, mileage.id()), null, null);
        assertTrue(item(view, mileage.id()).reviewReasons().contains("MILEAGE_NEEDS_REVIEW"));
        view = edit(services, view, new SetField(mileage.id(), "distanceKm", "20"));
        assertMileage(item(view, mileage.id()), "20", 1800L);
        assertEquals(4800L, view.confirmedTotal());
        assertEquals(List.of(Component.PARKING), view.claims().stream().map(claim -> claim.component()).toList());
        assertTrue(view.overrides().containsAll(List.of(fuel, link, raw)));
        assertOldFuelChangesAreRejected(services, view, mileage.id(), parking.units().getFirst().unitId());
        replaceMileagePage(services, view, screenshot.relativePath(), "selectedRouteDistanceKm", "12.35", PageParser.VERSION);
        view = edit(services, view, new DraftService.RestoreField(mileage.id(), "distanceKm"));
        assertMileage(item(view, mileage.id()), "24.70", 2223L);
        assertEquals("12.35", item(view, mileage.id()).fields().get("rawDistanceKm"));
        assertTrue(view.overrides().contains(raw));
    }

    @Test
    void mileageSourceReplacementHoldsDerivedFuelAndCopiesNeverDoubleCount() throws Exception {
        var services = setup();
        String path = "甲-乙-20261101.png";
        var view = scanMileage(services, path);
        replaceMileagePage(services, view, path, "selectedRouteDistanceKm", "12.35", PageParser.VERSION);
        view = edit(services, view);
        Path image = view.info().folder().resolve(path);
        Files.copy(image, image.resolveSibling("甲-乙-20261102.png"));
        view = services.scan(view.info().id(), view.info().revision());
        assertEquals(1, view.items().size());
        assertEquals(1, view.attachments().size());
        assertMileage(view.items().getFirst(), "12.35", 1112L);
        Files.delete(image.resolveSibling("甲-乙-20261102.png"));
        writeImage(image, 0xFFAA00);
        view = services.scan(view.info().id(), view.info().revision());
        replaceMileagePage(services, view, path, "selectedRouteDistanceKm", "30", PageParser.VERSION);
        view = edit(services, view);
        assertNull(view.confirmedTotal());
        assertTrue(view.items().getFirst().reviewReasons().contains("SOURCE_REVIEW_REQUIRED"));
        var changed = view.sources().stream().filter(source -> !source.missing()).findFirst().orElseThrow();
        view = edit(services, view, new DraftService.AcceptChangedSource(changed.docId()));
        assertMileage(view.items().getFirst(), "30", 2700L);
        assertEquals(1, view.attachments().size());
    }

    @Test
    void samePathChangedBytesAwaitReviewAndThenRecoverWithoutAnOldGhostPage() throws Exception {
        var services = setup();
        var folder = services.batch().resolve("杭州出差-2026.11.03");
        var info = services.cases().openCase(services.batch(), folder);
        var first = services.scan(info.id(), 0);
        var parking = first.attachments().getLast();
        Files.writeString(folder.resolve(parking.relativePath()), "\n% changed source\n", java.nio.file.StandardOpenOption.APPEND);
        var changed = services.scan(info.id(), first.info().revision());
        assertEquals(12879L, changed.confirmedTotal());
        assertEquals(8, changed.attachments().size());
        assertTrue(changed.unavailable().isEmpty());
        var version = changed.overrides().stream().filter(row -> row.fieldKey().equals("SOURCE_VERSION"))
                .findFirst().orElseThrow();
        assertEquals("PENDING", version.action());
        assertNotEquals(parking.docId(), version.targetId());
        var accepted = services.drafts().apply(info.id(), changed.info().revision(),
                List.of(new DraftService.AcceptChangedSource(version.targetId())));
        assertEquals(15879L, accepted.confirmedTotal());
        assertEquals(changed.info().revision() + 1, accepted.info().revision());
        assertEquals(accepted.info().revision(), services.scan(info.id(), accepted.info().revision()).info().revision());
    }

    @Test
    void typeChangeKeepsUnmappedAmountsUntilTheUserMakesAnExplicitChoice() throws Exception {
        var services = setup();
        var info = services.cases().openCase(services.batch(), services.batch().resolve("杭州出差-2026.10.21"));
        var first = services.scan(info.id(), 0);
        assertEquals(38275L, first.confirmedTotal());
        var target = expenseassistant.intake.TypeInference.CaseType.TAXI;
        var changed = services.drafts().apply(info.id(), first.info().revision(), List.of(new DraftService.SetType(target)));
        assertEquals(target, changed.info().type());
        var orphan = changed.items().stream().filter(item -> item.subtype().equals("HOTEL")).findFirst().orElseThrow();
        assertTrue(orphan.reviewReasons().contains("UNMAPPED_CONFIRMATION"));
        assertEquals(36465L, orphan.amounts().get(Component.HOTEL).confirmedMinor());
        var rescanned = services.scan(info.id(), changed.info().revision());
        assertEquals(changed.info().revision(), rescanned.info().revision());
        assertTrue(rescanned.items().stream().anyMatch(item -> item.reviewReasons().contains("UNMAPPED_CONFIRMATION")));
        var reviewed = services.drafts().apply(info.id(), rescanned.info().revision(),
                List.of(new DraftService.ClearAmount(orphan.id(), Component.HOTEL)));
        assertFalse(reviewed.items().stream().anyMatch(item -> item.reviewReasons().contains("UNMAPPED_CONFIRMATION")));
        assertEquals(1810L, reviewed.confirmedTotal());
    }

    @Test
    void unassignedPrintableEvidenceCanBecomeAManualExpense() throws Exception {
        var services = setup();
        var folder = Files.createDirectory(services.batch().resolve("自定义开支"));
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage(
                    new org.apache.pdfbox.pdmodel.common.PDRectangle(30, 30)));
            doc.save(folder.resolve("待分类.pdf").toFile());
        }
        var info = services.cases().openCase(services.batch(), folder);
        var scan = services.scan(info.id(), 0);
        assertEquals(1, scan.unassignedCount());
        assertEquals(1, scan.attachments().size());
        var command = new DraftService.AddExpense(scan.sources().getFirst().units().getFirst().unitId(),
                "人工费用", LocalDate.parse("2026-10-21"), null);
        var created = services.drafts().apply(info.id(), scan.info().revision(), List.of(
                new DraftService.SetType(expenseassistant.intake.TypeInference.CaseType.OTHER), command));
        assertEquals(0, created.unassignedCount());
        var item = created.items().getFirst();
        assertEquals("人工费用", item.description());
        assertEquals("MANUAL", item.subtype());
        assertEquals(created.info().revision(), services.drafts().apply(info.id(), created.info().revision(),
                List.of(command)).info().revision());
        var confirmed = services.drafts().apply(info.id(), created.info().revision(),
                List.of(new SetAmount(item.id(), Component.OTHER, "CNY", 0L, "人工核对为零")));
        assertEquals(0L, confirmed.confirmedTotal());
        assertTrue(confirmed.items().getFirst().reviewReasons().isEmpty());
    }

    private static void assertOldFuelChangesAreRejected(Services services, CaseView view, String itemId, String unitId) {
        for (var change : List.of(new SetAmount(itemId, Component.FUEL, "CNY", 99L, "手填油费"),
                new DraftService.ClearAmount(itemId, Component.FUEL),
                new DraftService.RestoreField(itemId, "AMOUNT:FUEL"),
                new LinkEvidence(itemId, unitId, Role.CHARGE, Component.FUEL, true))) {
            var failure = assertThrows(CaseException.class, () -> edit(services, view, change));
            assertTrue(failure.getMessage().contains("修改确认公里数"));
        }
        assertEquals(view, services.drafts().load(view.info().id()));
    }

    private static void rejectMileageEdit(Services services, CaseView view, SetField change) {
        var note = new SetField(view.items().getLast().id(), "note", "应随失败批次回滚");
        var failure = assertThrows(CaseException.class, () -> edit(services, view, note, change));
        assertEquals("MILEAGE_OUT_OF_RANGE", failure.code());
        assertEquals(view, services.drafts().load(view.info().id()));
    }

    private static void assertMileage(Item row, String km, Long cents) {
        assertEquals(km, row.fields().get("distanceKm"));
        var amount = row.amounts().get(Component.FUEL);
        if (cents == null) {
            assertNull(amount);
        } else {
            assertNotNull(amount);
            assertEquals(cents, amount.confirmedMinor());
            assertTrue(amount.basis().contains("0.90 元/公里"));
        }
    }

    private static Item item(CaseView view, String id) {
        return view.items().stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    private static CaseView edit(Services services, CaseView view, DraftService.Change... changes) {
        var info = view.info();
        return services.drafts().apply(info.id(), info.revision(), List.of(changes));
    }

    private static CaseView scanMileage(Services services, String... names) throws IOException {
        var folder = Files.createDirectory(services.batch().resolve("私车公用"));
        for (int index = 0; index < names.length; index++) {
            writeImage(folder.resolve(names[index]), (index + 1) * 0x161616);
        }
        var info = services.cases().openCase(services.batch(), folder);
        return services.scan(info.id(), info.revision());
    }

    private static void writeImage(Path path, int rgb) throws IOException {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        image.setRGB(32, 32, rgb);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    private static void replaceMileagePage(Services services, CaseView view, String path,
                                           String field, String value, String version) {
        var source = view.sources().stream().filter(row -> !row.missing() && row.relativePath().equals(path))
                .findFirst().orElseThrow();
        var page = new PageResult(1, PageResult.Kind.MILEAGE, true,
                List.of(new PageResult.FieldCandidate(field, value + "公里", value, PageResult.Method.OCR,
                        new PageResult.Bounds(0.1, 0.7, 0.1, 0.05), "受控 OCR 结果")), List.of(), List.of(), List.of(), version);
        services.database().transaction(connection -> {
            var store = new DraftStore(connection, view.info().id());
            store.putSource(new ScanService.SourceFile(view.info().folder().resolve(path), path, path, source.sha256(),
                    ScanService.Format.PNG), List.of(page));
            return null;
        });
    }

    private Services setup() throws IOException {
        Path batch = temporary.resolve("batch");
        SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        Path data = temporary.resolve("record");
        var database = Database.open(data.resolve("test.sqlite"));
        var paths = new PathPolicy(database);
        var cases = new CaseService(database, paths);
        var jobs = new CaseJobs();
        var scan = new ScanService(cases, paths, jobs);
        var recognition = RecognitionService.fromPaths(new AppPaths(temporary, data, temporary.resolve("cache"),
                temporary.resolve("out")));
        return new Services(batch, database, cases, scan, new DraftService(database, jobs, recognition), recognition);
    }

    private record Services(Path batch, Database database, CaseService cases, ScanService scans, DraftService drafts,
                            RecognitionService recognition) {
        CaseView scan(String id, long rev) {
            try (var ticket = scans.begin(id, rev)) {
                return drafts.mergeScan(scans.scan(ticket));
            }
        }
    }
}
