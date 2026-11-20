package expenseassistant.recognition;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import expenseassistant.recognition.PageResult.Kind;
import expenseassistant.recognition.PageResult.Method;
import expenseassistant.output.TemplateArchive;
import expenseassistant.support.ReceiptFixtures;
import expenseassistant.support.SampleCorpus;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecognitionServiceTest {
    @TempDir Path scratch;

    @Test
    void qualifiedPdfTextDoesNotInvokeOcr() throws Exception {
        Path batch = scratch.resolve("copy");
        var samples = SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), batch);
        AtomicInteger attempts = new AtomicInteger();
        var workspace = new OcrWorkspace(scratch);
        var ocr = new TesseractRunner(scratch.resolve("none"), scratch, workspace) {
            @Override
            OcrResult recognize(java.awt.image.BufferedImage img, String job, int page, int psm,
                                java.util.function.BooleanSupplier cancel) {
                attempts.incrementAndGet();
                return new OcrResult(null, "OCR_UNAVAILABLE");
            }
        };
        var service = new RecognitionService(ocr, workspace);
        for (var fixture : samples) {
            if ("APPLE_RECEIPT".equals(fixture.kind())) {
                continue;
            }
            var actual = service.extract(batch.resolve(fixture.relativePath()), fixture.sha256(), fixture.id());
            assertEquals(fixture.pages(), actual.size(), fixture.id());
            var page = actual.getFirst();
            assertEquals(Kind.valueOf(fixture.kind()), page.kind(), fixture.id());
            assertTrue(page.renderable(), fixture.id());
            var expected = fixture.expected();
            assertEquals(expected.get("gross"), page.value("gross"), fixture.id() + " gross");
            assertEquals(expected.get("currency"), page.value("currency"), fixture.id() + " currency");
            assertEquals(Method.PDF_TEXT, page.field("gross").orElseThrow().method(), fixture.id());
            assertNotNull(page.field("gross").orElseThrow().bounds(), fixture.id());
            if (page.kind() == Kind.INVOICE) {
                assertEquals(expected.get("invoiceNumberSha256"), sha(page.value("invoiceNumber")), fixture.id());
                assertEquals(expected.get("invoiceDate"), page.value("invoiceDate"), fixture.id());
            }
            if (expected.containsKey("segmentAmounts")) {
                assertEquals(List.of(7300L, 5800L), page.segments().stream().map(PageResult.TripSegment::minor).toList());
            }
            if (expected.containsKey("invoiceCount")) {
                assertEquals(5, page.summaryReferences().stream().map(PageResult.SummaryReference::invoiceKey).distinct().count(),
                        fixture.id() + " summary");
                assertEquals(expected.get("businessDate"), page.value("startDate"), fixture.id());
            }
        }
        assertEquals(0, attempts.get());
        assertWorkspaceEmpty();
    }

    @Test
    void flightInvoiceExtractsLabelledEndpointsAndBusinessDatesWithProvenance() {
        var page = controlledInvoice("""
                国际航空机票服务
                出发地：宁波  到达地：深圳
                起飞日期：2026年11月12日
                到达时间：2026年11月13日 00:20
                """);
        assertEquals("FLIGHT", page.value("subtype"));
        assertEquals("宁波", page.value("origin"));
        assertEquals("深圳", page.value("destination"));
        assertEquals("2026-11-12", page.value("startDate"));
        assertEquals("2026-11-13", page.value("endDate"));
        assertEquals("2026-11-15", page.value("invoiceDate"));
        var origin = page.field("origin").orElseThrow();
        assertEquals("宁波", origin.raw());
        assertEquals(Method.PDF_TEXT, origin.method());
        assertEquals(new PageResult.Bounds(0.1, 0.1, 0.5, 0.5), origin.bounds());
        assertEquals(Long.valueOf(79600), page.faceMinor());
    }

    @Test
    void travelStructuresAndIncompleteRoutesKeepTheirIndependentEvidence() {
        var flight = controlledInvoice("""
                国内机票服务
                航段：深圳（SZX）-宁波（NGB）
                乘机日期：2026-11-13
                """);
        assertEquals("深圳", flight.value("origin"));
        assertEquals("宁波", flight.value("destination"));
        assertEquals("2026-11-13", flight.value("startDate"));
        assertEquals("2026-11-13", flight.value("endDate"));

        var rail = controlledInvoice("""
                铁路电子客票
                宁波站 G1234 深圳北站
                2026年11月12日 08:25开
                """);
        assertEquals("RAIL", rail.value("subtype"));
        assertEquals("宁波站", rail.value("origin"));
        assertEquals("深圳北站", rail.value("destination"));
        assertEquals("2026-11-12", rail.value("startDate"));
        assertEquals("2026-11-12", rail.value("endDate"));

        var incomplete = controlledInvoice("铁路电子客票\n出发站：宁波站\n到达站：\n");
        assertEquals("宁波站", incomplete.value("origin"));
        assertNull(incomplete.value("destination"));
        assertNull(incomplete.value("startDate"));
        assertNull(incomplete.value("endDate"));
        assertEquals("2026-11-15", incomplete.value("invoiceDate"));
        assertEquals(Long.valueOf(79600), incomplete.faceMinor());
    }

    @Test
    void completeTravelFieldsDoNotMakeAConflictingAmountReliable() {
        var page = controlledInvoice("""
                铁路电子客票
                出发站：宁波站 到达站：深圳北站
                乘车日期：2026-11-12
                合计 ¥700.00 ¥95.00
                """);
        assertEquals("深圳北站", page.value("destination"));
        assertNull(page.value("gross"));
        assertTrue(page.warnings().contains("AMOUNT_CONFLICT"));
        assertFalse(new PageParser().reliable(page));
    }

    private static PageResult controlledInvoice(String ticketFields) {
        var text = new PageText.Builder();
        text.append("""
                电子发票
                发票号码：10000000000000000001
                开票日期：2026年11月15日
                价税合计（小写）￥796.00
                """ + ticketFields, new PageResult.Bounds(0.1, 0.1, 0.5, 0.5));
        return new PageParser().extractFields(text.toPageText(Method.PDF_TEXT), 1, PageParser.VERSION);
    }

    @Test
    void explicitCurrenciesOnReceiptsAndInvoicesUseTheWholeTotal() {
        var receipt = parsed("""
                Receipt
                Issued by: Example Services
                Order ID: ORDER-100
                Receipt No: RC-123
                Transaction Date: 2026-11-20
                Total: US$1,234.56
                All amounts are in USD
                """);
        assertEquals(Kind.RECEIPT, receipt.kind());
        assertEquals("Example Services", receipt.value("receiptIssuer"));
        assertEquals("ORDER-100", receipt.value("orderKey"));
        assertEquals("RC-123", receipt.value("receiptNumber"));
        assertEquals("2026-11-20", receipt.value("receiptDate"));
        assertEquals("1,234.56", receipt.field("gross").orElseThrow().raw());
        assertEquals("1234.56", receipt.value("gross"));
        assertEquals("USD", receipt.value("currency"));
        assertNull(receipt.faceMinor());
        assertTrue(new PageParser().reliable(receipt));

        for (String total : List.of("Total: 100 USD", "总额：美元 100.0", "Total (USD): 100.00",
                "Invoice Total: USD $100", "Total amount due: 100\nAll prices in U.S. dollars",
                "Total: $100.00\nAll amounts in USD", "总额：100\n币种：USD")) {
            var invoice = parsed("发票号码：10000000000000000001\n开票日期：2026年11月20日\n" + total);
            assertEquals("100.00", invoice.value("gross"), total);
            assertEquals("USD", invoice.value("currency"), total);
            assertNull(invoice.faceMinor(), total);
            assertTrue(new PageParser().reliable(invoice), total);
        }
    }

    @Test
    void conflictingOrAmbiguousReceiptTotalsRemainUnconfirmed() {
        for (String amount : List.of("USD -100", "USD 1,20", "USD 1 200", "USD 1.234", "USD (100.00)")) {
            var page = parsed("Receipt\nTotal: " + amount);
            assertTrue(page.warnings().contains("AMOUNT_CONFLICT"), amount);
            assertNull(page.value("gross"), amount);
        }
        for (String amount : List.of("$100.00", "100.00\nUSD figures for reference")) {
            var page = parsed("Receipt\nTotal: " + amount);
            assertTrue(page.warnings().contains("CURRENCY_NEEDS_REVIEW"), amount);
            assertNull(page.value("currency"), amount);
        }
        var copy = parsed("Receipt\nTotal: USD 100\nTotal: USD 100.00");
        assertEquals("100.00", copy.value("gross"));
        var differentAmount = parsed("Receipt\nTotal: USD 100\nTotal: USD 101.00");
        assertTrue(differentAmount.warnings().contains("AMOUNT_CONFLICT"));
        assertNull(differentAmount.value("gross"));
        var conflict = parsed("Receipt\nTotal: CNY 100.00\nAll amounts in USD");
        assertTrue(conflict.warnings().contains("CURRENCY_CONFLICT"));
        assertNull(conflict.value("gross"));
        assertNull(conflict.value("currency"));
        assertNull(conflict.faceMinor());
        assertEquals(List.of("USD", "CNY"),
                conflict.fields().stream().filter(f -> "currency".equals(f.key())).map(PageResult.FieldCandidate::raw).toList());
    }

    @Test
    void onlySameCurrencyTaxAndNetParticipateInUsdReconciliation() {
        var mixed = parsed("Receipt\nTotal: USD 100\nSubtotal: USD 90.00\nTax: CNY 60.00");
        assertEquals("100.00", mixed.value("gross"));
        var conflict = parsed("Receipt\nTotal: USD 100\nSubtotal: USD 90.00\nTax: USD 15.00");
        assertTrue(conflict.warnings().contains("AMOUNT_CONFLICT"));
        assertNull(conflict.value("gross"));
        var apple = parsed("""
                Apple Receipt
                2026-11-20
                Order ID: ORDER123
                ChatGPT Pro 100
                $100.00
                Billing: United States
                Currency: CNY
                """);
        assertTrue(apple.warnings().contains("CURRENCY_CONFLICT"));
        assertNull(apple.value("gross"));
    }

    @Test
    void mergingPdfAndOcrPreservesConflictingCandidatesAndRejectsBothBillingPaths() throws Exception {
        var badCurrency = mergedReceipt("CNY 100.00", "USD 100.00");
        assertTrue(badCurrency.warnings().contains("CURRENCY_CONFLICT"));
        assertNull(badCurrency.value("currency"));
        assertNull(badCurrency.value("gross"));
        assertNull(badCurrency.faceMinor());
        assertEquals(List.of(Method.PDF_TEXT, Method.OCR), badCurrency.fields().stream()
                .filter(f -> "gross".equals(f.key())).map(PageResult.FieldCandidate::method).toList());

        var badAmount = mergedReceipt("USD 100", "USD 101.00");
        assertTrue(badAmount.warnings().contains("AMOUNT_CONFLICT"));
        assertNull(badAmount.value("gross"));
        var existingConflict = mergedReceipt("CNY 100\nAll amounts in USD", "USD 100.00");
        assertTrue(existingConflict.warnings().contains("CURRENCY_CONFLICT"));
        assertNull(existingConflict.value("gross"));
        assertNull(existingConflict.value("currency"));
        var resolved = mergedReceipt("$100", "USD 100.00");
        assertEquals("USD", resolved.value("currency"));
        assertEquals("100.00", resolved.value("gross"));
        assertTrue(new PageParser().reliable(resolved));
        assertWorkspaceEmpty();
    }

    private PageResult mergedReceipt(String pdfTotal, String ocrTotal) throws Exception {
        Path pdf = Files.createTempFile(scratch, "receipt-", ".pdf");
        ReceiptFixtures.writePdf(pdf, "Receipt\nTotal: " + pdfTotal);
        var workspace = new OcrWorkspace(scratch);
        var ocr = new TesseractRunner(scratch.resolve("unused"), scratch, workspace) {
            @Override
            OcrResult recognize(BufferedImage image, String job, int page, int psm,
                                java.util.function.BooleanSupplier cancel) {
                var text = new PageText.Builder();
                text.append("Receipt\nMerchant: Shop\nOrder ID: A00123\nDate: 2026-11-20\nTotal: " + ocrTotal, null);
                return new OcrResult(text.toPageText(Method.OCR), null);
            }
        };
        return new RecognitionService(ocr, workspace).extract(pdf, TemplateArchive.sha256(pdf), pdf.getFileName().toString())
                .getFirst();
    }

    private static PageResult parsed(String input) {
        var text = new PageText.Builder();
        text.append(input, new PageResult.Bounds(0.1, 0.1, 0.5, 0.5));
        return new PageParser().extractFields(text.toPageText(Method.PDF_TEXT), 1, PageParser.VERSION);
    }

    @Test
    void realAppleReceiptsUseTheTopDateAndItemAmount() throws Exception {
        String configured = System.getProperty("expenseassistant.ocrRoot");
        assumeTrue(configured != null && !configured.isBlank(), "需指定经校验的 OCR 组件目录");
        Path root = Path.of(configured);
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var workspace = new OcrWorkspace(scratch);
        var runner = new TesseractRunner(root.resolve(windows ? "bin/tesseract.exe" : "bin/tesseract"),
                root.resolve("share/tessdata"), workspace);
        assertTrue(runner.available());
        var service = new RecognitionService(runner, workspace);
        for (var fixture : SampleCorpus.verify(SampleCorpus.defaultSource())) {
            if (!"APPLE_RECEIPT".equals(fixture.kind())) {
                continue;
            }
            var expected = fixture.expected();
            var page = service.extract(SampleCorpus.defaultSource().resolve(fixture.relativePath()),
                    fixture.sha256(), fixture.id()).getFirst();
            assertEquals(Kind.APPLE_RECEIPT, page.kind(), fixture.id());
            assertEquals(expected.get("receiptDate"), page.value("receiptDate"), fixture.id());
            assertEquals(expected.get("gross"), page.value("gross"), fixture.id());
            assertEquals("USD", page.value("currency"), fixture.id());
            assertEquals(expected.get("orderSha256"), sha(page.value("orderKey")), fixture.id());
            assertEquals(expected.get("documentSha256"), sha(page.value("documentNumber")), fixture.id());
            assertEquals(expected.get("item"), page.value("item"), fixture.id());
            if ("false".equals(expected.get("eligibleAutomaticRenewal"))) {
                assertNull(page.value("renewalDate"), fixture.id());
            }
        }
        assertWorkspaceEmpty();
    }

    @Test
    void mixedPdfFallbackTouchesOnlyTheImagePageAndBadInputStaysSeparate() throws Exception {
        var sample = SampleCorpus.verify(SampleCorpus.defaultSource()).stream()
                .filter(entry -> entry.id().equals("P01")).findFirst().orElseThrow();
        Path original = SampleCorpus.defaultSource().resolve(sample.relativePath());
        Path mixed = scratch.resolve("mixed.pdf");
        var rendered = new java.awt.image.BufferedImage(100, 80, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (var text = org.apache.pdfbox.Loader.loadPDF(original.toFile());
             var combined = new org.apache.pdfbox.pdmodel.PDDocument()) {
            combined.importPage(text.getPage(0));
            var imagePage = new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(100, 80));
            combined.addPage(imagePage);
            var picture = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(combined, rendered);
            try (var canvas = new org.apache.pdfbox.pdmodel.PDPageContentStream(combined, imagePage)) {
                canvas.drawImage(picture, 0, 0);
            }
            combined.save(mixed.toFile());
        }
        var workspace = new OcrWorkspace(scratch);
        var pagesCalled = new java.util.ArrayList<Integer>();
        var ocr = new TesseractRunner(scratch.resolve("not-installed"), scratch, workspace) {
            @Override
            OcrResult recognize(java.awt.image.BufferedImage img, String job, int page, int psm,
                                java.util.function.BooleanSupplier cancel) {
                pagesCalled.add(page);
                var text = new PageText.Builder();
                text.append("候选里程 12.35 公里", null);
                return new OcrResult(text.toPageText(Method.OCR), null);
            }
        };
        var service = new RecognitionService(ocr, workspace);
        var pages = service.extract(mixed, expenseassistant.output.TemplateArchive.sha256(mixed), "mixed");
        assertEquals(List.of(2), pagesCalled);
        assertEquals("131.00", pages.getFirst().value("gross"));
        assertEquals("12.35", pages.getLast().value(MileageRouteSelector.CANDIDATE));
        assertEquals(Method.OCR, pages.getLast().field(MileageRouteSelector.CANDIDATE).orElseThrow().method());
        assertNull(pages.getLast().value(MileageRouteSelector.SELECTED));
        assertTrue(pages.getLast().warnings().contains(MileageRouteSelector.NEEDS_REVIEW));

        var broken = scratch.resolve("broken.pdf");
        Files.writeString(broken, "%PDF-1.7 invalid bytes");
        var failure = service.extract(broken, expenseassistant.output.TemplateArchive.sha256(broken), "broken").getFirst();
        assertFalse(failure.renderable());
        assertTrue(failure.warnings().contains("PAGE_UNRENDERABLE"));
        assertEquals("SOURCE_CHANGED", assertThrows(RecognitionService.RecognitionFailure.class,
                () -> service.extract(broken, sample.sha256(), "changed")).code());
        assertWorkspaceEmpty();
    }

    @Test
    void realRouteScreenshotsKeepEveryCandidateAndSelectTheBlueRoute() throws Exception {
        String configured = System.getProperty("expenseassistant.ocrRoot");
        String samples = System.getProperty("expenseassistant.privateCarSampleRoot");
        assumeTrue(configured != null && samples != null, "需指定实图目录及 OCR 组件");
        Path root = Path.of(configured);
        var workspace = new OcrWorkspace(scratch);
        var runner = new TesseractRunner(root.resolve(isWindows() ? "bin/tesseract.exe" : "bin/tesseract"),
                root.resolve("share/tessdata"), workspace);
        assertTrue(runner.available());
        var service = new RecognitionService(runner, workspace);
        assertRouteScreenshot(service, Path.of(samples, "杭州-宁波-20261103.jpg"), "167", List.of("167", "178", "192"));
        assertRouteScreenshot(service, Path.of(samples, "余姚-公司-20261118.jpg"), "69", List.of("74", "69", "72"));
        assertWorkspaceEmpty();
    }

    private void assertRouteScreenshot(RecognitionService service, Path source, String km, List<String> distances)
            throws Exception {
        var copy = Files.copy(source, scratch.resolve(source.getFileName()));
        var pages = service.extract(copy, TemplateArchive.sha256(copy), "route");
        assertEquals(1, pages.size());
        var page = pages.getFirst();
        assertEquals(1, page.pageNo());
        assertEquals(Kind.MILEAGE, page.kind());
        assertTrue(page.renderable());
        assertEquals(distances, page.fields().stream().filter(field -> MileageRouteSelector.CANDIDATE.equals(field.key()))
                .sorted(Comparator.comparingDouble(field -> field.bounds().x()))
                .map(PageResult.FieldCandidate::normalized).toList());
        var selected = page.field(MileageRouteSelector.SELECTED).orElseThrow(() -> new AssertionError(page));
        assertEquals(km, selected.normalized());
        assertTrue(selected.raw().contains(km));
        assertEquals(Method.OCR, selected.method());
        assertNotNull(selected.bounds());
        assertTrue(page.fields().stream().anyMatch(candidate -> MileageRouteSelector.CANDIDATE.equals(candidate.key())
                && candidate.raw().equals(selected.raw()) && selected.bounds().equals(candidate.bounds())));
        assertFalse(page.warnings().contains(MileageRouteSelector.NEEDS_REVIEW));
    }

    @Test
    void routeSelectionUsesAlignedCardsRatherThanDistanceOrderScaleOrButton() throws Exception {
        var first = new RouteFixture(50);
        first.card(70, "167", ROUTE_BLUE, ROUTE_BLUE);
        first.card(360, "74", ROUTE_GRAY, ROUTE_GRAY);
        first.card(650, "192", ROUTE_GRAY, ROUTE_GRAY);
        first.word("3公里", 10, 250, 70, 25, 1, ROUTE_BLUE);
        first.word("79km/h", 20, 280, 80, 30, 1, ROUTE_BLUE);
        first.word("79 公里 / 小时", 25, 315, 120, 26, 1, ROUTE_BLUE);
        first.word("开始导航", 650, 1050, 250, 40, 1, ROUTE_BLUE);
        var firstPage = readRoute(first, false);
        assertEquals("167", firstPage.value(MileageRouteSelector.SELECTED));
        assertEquals(List.of("3", "167", "74", "192"), candidateDistances(firstPage));

        var middle = new RouteFixture(150);
        middle.card(70, "74", ROUTE_GRAY, ROUTE_GRAY);
        middle.card(360, "69", ROUTE_BLUE, ROUTE_BLUE);
        middle.card(650, "69", ROUTE_GRAY, ROUTE_GRAY);
        middle.word("…8公里", 925, 895, 70, 28, 2, ROUTE_BLUE);
        var middlePage = readRoute(middle, true);
        assertEquals("69", middlePage.value(MileageRouteSelector.SELECTED));
        assertEquals(0.36, middlePage.field(MileageRouteSelector.SELECTED).orElseThrow().bounds().x(), 0.001);
        assertEquals(List.of("74", "69", "69"), candidateDistances(middlePage));
    }

    @Test
    void splitDecimalKeepsPrecisionAndOriginalDistanceEvidence() throws Exception {
        var fixture = new RouteFixture(100);
        fixture.word("28分钟", 320, 850, 130, 31, 1, ROUTE_BLUE);
        fixture.word("12", 320, 895, 25, 28, 2, ROUTE_BLUE);
        fixture.word(".", 347, 915, 4, 7, 2, ROUTE_BLUE);
        fixture.word("350", 353, 895, 37, 28, 3, ROUTE_BLUE);
        fixture.word("公", 395, 895, 21, 31, 3, ROUTE_BLUE);
        fixture.word("里", 420, 895, 21, 31, 3, ROUTE_BLUE);
        fixture.word("2", 930, 940, 13, 28, 4, ROUTE_BLUE);
        fixture.word("公里", 930, 980, 44, 28, 4, ROUTE_BLUE);
        var page = readRoute(fixture, false);
        assertEquals(List.of("12.350"), candidateDistances(page));
        var selected = page.field(MileageRouteSelector.SELECTED).orElseThrow(() -> new AssertionError(page));
        assertEquals("12.350", selected.normalized());
        assertTrue(selected.raw().contains(".\n350"));
        assertEquals(0.32, selected.bounds().x(), 0.001);
        assertEquals(0.121, selected.bounds().width(), 0.001);
    }

    @Test
    void absentConflictingAndInsufficientRouteColorsLeaveThePagePrintable() throws Exception {
        assertRouteNeedsReview(ROUTE_GRAY, ROUTE_GRAY, ROUTE_GRAY, ROUTE_GRAY);
        assertRouteNeedsReview(ROUTE_BLUE, ROUTE_BLUE, ROUTE_BLUE, ROUTE_BLUE);
        assertRouteNeedsReview(ROUTE_BLUE, ROUTE_GRAY, ROUTE_GRAY, ROUTE_GRAY);
        assertRouteNeedsReview(new Color(220, 229, 242), new Color(220, 229, 242), ROUTE_GRAY, ROUTE_GRAY);
    }

    @Test
    void missingDistanceOnAnIdentifiableNavigationPageStillAsksForMileageReview() throws Exception {
        var fixture = new RouteFixture(100);
        fixture.word("28分钟", 70, 850, 180, 31, 1, ROUTE_BLUE);
        fixture.word("开始导航", 650, 1050, 250, 40, 2, ROUTE_BLUE);
        var page = readRoute(fixture, false);
        assertEquals(Kind.MILEAGE, page.kind());
        assertTrue(page.renderable());
        assertTrue(candidateDistances(page).isEmpty());
        assertNull(page.value(MileageRouteSelector.SELECTED));
        assertTrue(page.warnings().contains(MileageRouteSelector.NEEDS_REVIEW));
    }

    private void assertRouteNeedsReview(Color firstTime, Color firstKm, Color secondTime, Color secondKm) throws Exception {
        var fixture = new RouteFixture(100);
        fixture.card(70, "10", firstTime, firstKm);
        fixture.card(360, "20", secondTime, secondKm);
        var page = readRoute(fixture, false);
        assertEquals(List.of("10", "20"), candidateDistances(page));
        assertNull(page.value(MileageRouteSelector.SELECTED));
        assertTrue(page.renderable());
        assertTrue(page.warnings().contains(MileageRouteSelector.NEEDS_REVIEW));
    }

    @Test
    void mileagePdfTextCandidatesUseTheRenderedPageForSelectionAndRetry() throws Exception {
        var fixture = new RouteFixture(100);
        fixture.card(70, "18", ROUTE_BLUE, ROUTE_BLUE);
        fixture.card(360, "20", ROUTE_GRAY, ROUTE_GRAY);
        var input = scratch.resolve("route.pdf");
        writeMileagePdf(input, fixture.pixels());
        var attempts = new AtomicInteger();
        var service = routeService(fixture, attempts);
        String hash = TemplateArchive.sha256(input);
        var page = service.extract(input, hash, "route-pdf").getFirst();
        assertEquals(List.of("999", "18", "20"), candidateDistances(page));
        assertEquals("18", page.value(MileageRouteSelector.SELECTED));
        assertEquals(Method.OCR, page.field(MileageRouteSelector.SELECTED).orElseThrow().method());
        assertEquals(1, attempts.get());
        assertEquals(page, service.extract(input, hash, "route-cache").getFirst());
        assertEquals(1, attempts.get());
        assertEquals(page, service.retryPage(input, hash, "route-retry", 1, () -> false));
        assertEquals(2, attempts.get());
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> service.retryPage(input, hash, "route-cancelled", 1, () -> true));
        assertEquals(2, attempts.get());
        assertWorkspaceEmpty();
    }

    private static void writeMileagePdf(Path input, BufferedImage pixels) throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(240, 288));
            document.addPage(page);
            var photo = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, pixels);
            try (var canvas = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                canvas.drawImage(photo, 0, 0, 240, 288);
                canvas.beginText();
                canvas.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 10);
                canvas.newLineAtOffset(30, 250);
                canvas.showText("999 km");
                canvas.endText();
            }
            document.save(input.toFile());
        }
    }

    private static final Color ROUTE_BLUE = new Color(0x3375ff);
    private static final Color ROUTE_GRAY = new Color(0x131419);

    private PageResult readRoute(RouteFixture fixture, boolean rotate) throws Exception {
        var input = Files.createTempFile(scratch, "route-", rotate ? ".jpg" : ".png");
        if (rotate) {
            var compressed = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(PageImages.orient(fixture.pixels(), 8), "jpg", compressed));
            byte[] jpg = compressed.toByteArray();
            try (var output = Files.newOutputStream(input)) {
                output.write(jpg, 0, 2);
                output.write(HexFormat.of().parseHex("ffe1002245786966000049492a0008000000010012010300010000000600000000000000"));
                output.write(jpg, 2, jpg.length - 2);
            }
            assertEquals(6, PageImages.decode(input).orientation());
        } else {
            assertTrue(ImageIO.write(fixture.pixels(), "png", input.toFile()));
        }
        var service = routeService(fixture, new AtomicInteger());
        var page = service.extract(input, TemplateArchive.sha256(input), "route-fixture").getFirst();
        assertWorkspaceEmpty();
        return page;
    }

    private RecognitionService routeService(RouteFixture fixture, AtomicInteger attempts) throws Exception {
        var workspace = new OcrWorkspace(scratch);
        var executable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Path languageDir = Files.createTempDirectory(scratch, "langs-");
        Files.createFile(languageDir.resolve("chi_sim.traineddata"));
        Files.createFile(languageDir.resolve("eng.traineddata"));
        Path text = Files.createTempFile(scratch, "route-", ".tsv");
        Files.write(text, fixture.tsv);
        var runner = new TesseractRunner(executable, languageDir, workspace, Duration.ofSeconds(8), command -> {
            attempts.incrementAndGet();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            command.command(executable.toString(), "-cp", classpath, TsvChild.class.getName(), text.toString());
            return command.start();
        });
        return new RecognitionService(runner, workspace);
    }

    private static List<String> candidateDistances(PageResult page) {
        return page.fields().stream().filter(field -> MileageRouteSelector.CANDIDATE.equals(field.key()))
                .map(PageResult.FieldCandidate::normalized).toList();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    public static class TsvChild {
        public static void main(String[] args) throws Exception {
            Files.copy(Path.of(args[0]), System.out);
        }
    }

    private static final class RouteFixture {
        private final int scale;
        private final BufferedImage pixels;
        private final List<String> tsv = new ArrayList<>();

        RouteFixture(int scale) {
            this.scale = scale;
            pixels = new BufferedImage(size(1000), size(1200), BufferedImage.TYPE_INT_RGB);
            var canvas = pixels.createGraphics();
            try {
                canvas.setColor(Color.WHITE);
                canvas.fillRect(0, 0, pixels.getWidth(), pixels.getHeight());
            } finally {
                canvas.dispose();
            }
        }

        BufferedImage pixels() {
            return pixels;
        }

        void card(int x, String km, Color timeColor, Color distanceColor) {
            word("1小时12分", x, 850, 180, 31, 1, timeColor);
            word(km + "公里", x, 895, 100, 28, 2, distanceColor);
        }

        void word(String value, int x, int y, int width, int height, int line, Color ink) {
            tsv.add(String.join("\t", "5", "1", "1", "1", Integer.toString(line), Integer.toString(tsv.size() + 1),
                    Integer.toString(size(x)), Integer.toString(size(y)), Integer.toString(size(width)),
                    Integer.toString(size(height)), "95", value));
            var canvas = pixels.createGraphics();
            try {
                canvas.setColor(ink);
                int step = Math.max(1, width / 8);
                for (int offset = 0; offset < width; offset += step) {
                    canvas.fillRect(size(x + offset), size(y + 1), Math.max(1, size(step / 3)), size(height - 2));
                }
            } finally {
                canvas.dispose();
            }
        }

        private int size(int value) {
            return (int) Math.round(value * scale / 100.0);
        }
    }

    @Test
    void ocrProcessOutcomesArePrivateAndReleaseTheSingleWorker() throws Exception {
        var workspace = new OcrWorkspace(scratch);
        var executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path languages = Files.createDirectory(scratch.resolve("langs"));
        Files.createFile(languages.resolve("chi_sim.traineddata"));
        Files.createFile(languages.resolve("eng.traineddata"));
        var modes = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of("block", "success", "fail", "block"));
        var blockingStarted = new java.util.concurrent.CountDownLatch(1);
        var launches = new AtomicInteger();
        var runner = new TesseractRunner(executable, languages, workspace, java.time.Duration.ofSeconds(1), command -> {
            String mode = modes.remove();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            command.command(executable.toString(), "-cp", classpath, OcrChild.class.getName(), mode);
            Process process = command.start();
            if (launches.incrementAndGet() == 4) {
                blockingStarted.countDown();
            }
            return process;
        });
        var log = java.util.logging.Logger.getLogger(TesseractRunner.class.getName());
        var messages = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord event) { messages.add(event.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        var previous = log.getLevel();
        var propagate = log.getUseParentHandlers();
        log.setUseParentHandlers(false);
        log.setLevel(java.util.logging.Level.FINE);
        log.addHandler(listener);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var image = new java.awt.image.BufferedImage(36, 36, java.awt.image.BufferedImage.TYPE_INT_RGB);
            assertEquals("OCR_TIMEOUT", runner.recognize(image, "job", 1, 3, () -> false).error());
            assertWorkspaceEmpty();
            var success = runner.recognize(image, "job", 2, 3, () -> false);
            assertNull(success.error());
            assertTrue(success.text().text().contains("OCR_PRIVATE_SENTINEL"));
            assertWorkspaceEmpty();
            assertEquals("OCR_FAILED", runner.recognize(image, "job", 3, 3, () -> false).error());
            assertWorkspaceEmpty();
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var task = worker.submit(() -> runner.recognize(image, "job", 4, 3, cancelled::get));
            assertTrue(blockingStarted.await(4, java.util.concurrent.TimeUnit.SECONDS));
            cancelled.set(true);
            runner.cancel("job");
            var cancellation = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(4, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, cancellation.getCause());
            assertWorkspaceEmpty();
            assertEquals(4, messages.size());
            assertTrue(messages.stream().allMatch(text -> text.matches(
                    "job=job stage=ocr page=[1-4] elapsedMs=[0-9]+ code=(OK|OCR_FAILED|OCR_TIMEOUT|CANCELLED)")));
            assertTrue(messages.stream().noneMatch(text -> text.contains("OCR_PRIVATE_SENTINEL")
                    || text.contains("MOCK_TAX") || text.contains("MOCK_TEL")));
        } finally {
            log.removeHandler(listener);
            log.setLevel(previous);
            log.setUseParentHandlers(propagate);
        }
    }

    @Test
    void orphanCleanupRemovesOnlyMarkedOcrAttempts() throws Exception {
        var beforeRestart = new OcrWorkspace(scratch);
        var crashedAttempt = beforeRestart.create();
        Files.writeString(crashedAttempt.resolve("page.png"), "test payload");
        var foreign = Files.createDirectory(scratch.resolve("ocr/page-foreign"));
        var export = Files.createDirectories(scratch.resolve("export/.staged"));
        var afterRestart = new OcrWorkspace(scratch);
        var active = afterRestart.create();
        assertEquals(1, afterRestart.cleanupOrphans());
        assertFalse(Files.exists(crashedAttempt));
        assertTrue(Files.isDirectory(active));
        assertTrue(Files.isDirectory(foreign));
        assertTrue(Files.isDirectory(export));
        afterRestart.release(active);
    }

    private static String sha(String value) throws Exception {
        assertNotNull(value);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertWorkspaceEmpty() throws Exception {
        try (var entries = Files.list(scratch.resolve("ocr"))) {
            assertEquals(0, entries.count());
        }
    }
}
