package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.matching.ExpenseMatcher.Role;
import expenseassistant.recognition.AttachmentImages;
import expenseassistant.recognition.PageImages;
import expenseassistant.support.SampleCorpus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static expenseassistant.output.ExportSnapshot.Layout.FULL_PAGE;
import static expenseassistant.output.ExportSnapshot.Layout.TWO_PER_PAGE;
import static expenseassistant.recognition.AttachmentImages.CropMode.TRIP_STATEMENT;
import static org.junit.jupiter.api.Assertions.*;

class WordWriterTest {
    @TempDir Path temp;
    private final WordWriter writer = new WordWriter();

    @Test
    void productionWriterReproducesWpsProbeAndFourSheetExample() throws IOException {
        Path copied = temp.resolve("corpus");
        var samples = SampleCorpus.verifyAndCopy(SampleCorpus.defaultSource(), copied);
        var hangzhou = samples.stream().filter(entry -> entry.caseFolder().equals("杭州出差-2026.11.03"))
                .sorted(java.util.Comparator.comparing(SampleCorpus.Entry::id)).toList();
        assertEquals(8, hangzhou.size());
        var files = new HashMap<String, Path>();
        var pages = hangzhou.stream().map(entry -> {
            files.put(entry.id(), copied.resolve(entry.relativePath()));
            return new ExportSnapshot.AttachmentPage(entry.id(), entry.sha256(), entry.relativePath(), 1,
                    List.of("receipt"), Role.SUPPORT, "杭州出差，附件说明校验", TWO_PER_PAGE);
        }).toList();
        Path four = temp.resolve("four.docx");
        var release = snapshot(pages);
        writer.write(release, files, four);
        writer.verify(release, four);
        assertPageNumberFooter(four, 4);
        String probeDir = System.getProperty("expenseassistant.wordProbeOutput");
        Path probe = probeDir == null ? temp.resolve("two.docx")
                : Path.of(probeDir).resolve("正式写入器-双票版式.docx");
        SampleCorpus.checkOutsideSource(SampleCorpus.defaultSource(), probe);
        var paired = pages.stream().filter(page -> List.of("P06", "P07").contains(page.docId()))
                .sorted(java.util.Comparator.comparing(ExportSnapshot.AttachmentPage::docId).reversed()).toList();
        assertEquals(2, paired.size());
        writer.write(snapshot(paired), files, probe);
        writer.verify(snapshot(paired), probe);
        assertPageNumberFooter(probe, 1);
    }

    @Test
    void fullPageClosesPreviousSlotAndMaintainsMultiPageOrder() throws IOException {
        Path source = twoPagePdf();
        var first = page("a", 1, "第一行\n第二行", TWO_PER_PAGE);
        var full = page("b", 1, "长附件", FULL_PAGE);
        var second = page("a", 2, "", TWO_PER_PAGE);
        var pages = List.of(first, full, second);
        var sheets = AttachmentLayout.paginate(pages);
        assertEquals(List.of(List.of(first), List.of(full), List.of(second)),
                sheets.stream().map(AttachmentLayout.Sheet::pages).toList());
        assertEquals(List.of(false, true, false), sheets.stream().map(AttachmentLayout.Sheet::fullPage).toList());
        Path output = temp.resolve("mixed.docx");
        var release = snapshot(pages);
        writer.write(release, Map.of("a", source, "b", source), output);
        writer.verify(release, output);
        assertPageNumberFooter(output, 3);
        try (var document = new XWPFDocument(Files.newInputStream(output))) {
            assertEquals(3, document.getTables().size());
            assertEquals("", document.getTables().getFirst().getRow(2).getCell(0).getText());
            assertEquals(1, document.getTables().get(1).getNumberOfRows());
        }
    }

    @Test
    void rejectsAnAttachmentWithMissingTotalPageField() throws IOException {
        Path source = twoPagePdf();
        Path valid = temp.resolve("numbered.docx");
        Path damaged = temp.resolve("missing-total.docx");
        var release = snapshot(List.of(page("a", 1, "凭证", TWO_PER_PAGE)));
        writer.write(release, Map.of("a", source), valid);
        try (var input = Files.newInputStream(valid); var document = new XWPFDocument(input);
             var destination = Files.newOutputStream(damaged)) {
            var paragraph = document.getHeaderFooterPolicy().getDefaultFooter().getParagraphs().getFirst();
            assertEquals("NUMPAGES", paragraph.getRuns().get(8).getCTR().getInstrTextArray(0).getStringValue().strip());
            for (int index = 11; index >= 7; index--) {
                assertTrue(paragraph.removeRun(index));
            }
            document.write(destination);
        }
        assertEquals("附件页码域校验失败", assertThrows(IOException.class, () -> writer.verify(release, damaged)).getMessage());
    }

    @Test
    void bothLayoutsEmbedTheCroppedPageAndInvoicePdfKeepsEveryPixel() throws IOException {
        Path source = markedPdf();
        var upper = new ExportSnapshot.AttachmentPage("trips", "sha", "trips.pdf", 1,
                List.of("receipt"), Role.SUPPORT, "行程上格", TWO_PER_PAGE, TRIP_STATEMENT);
        var full = new ExportSnapshot.AttachmentPage("trips", "sha", "trips.pdf", 2,
                List.of("receipt"), Role.SUPPORT, "行程整页", FULL_PAGE, TRIP_STATEMENT);
        var invoice = page("invoice", 1, "PDF 发票", TWO_PER_PAGE);
        Path output = temp.resolve("cropped.docx");
        var release = snapshot(List.of(upper, full, invoice));
        writer.write(release, Map.of("trips", source, "invoice", source), output);
        writer.verify(release, output);

        float dpi = AttachmentLayout.SETTINGS.imageDpi();
        var whole = PageImages.render(source, 1, dpi);
        var upperCrop = AttachmentImages.render(source, 1, dpi, TRIP_STATEMENT);
        var fullCrop = AttachmentImages.render(source, 2, dpi, TRIP_STATEMENT);
        assertTrue(upperCrop.getWidth() < whole.getWidth());
        assertTrue(upperCrop.getHeight() < whole.getHeight());
        assertNotEquals(upperCrop.getHeight(), fullCrop.getHeight());
        try (var doc = new XWPFDocument(Files.newInputStream(output))) {
            assertEmbeddedPixels(upperCrop, doc, 0, AttachmentLayout.SETTINGS.imageHeightMm());
            assertEmbeddedPixels(fullCrop, doc, 1, AttachmentLayout.SETTINGS.fullImageMm());
            assertEmbeddedPixels(whole, doc, 2, AttachmentLayout.SETTINGS.imageHeightMm());
        }
    }

    @Test
    void overflowingCaptionAndBadPageDoNotProduceOutput() throws IOException {
        Path source = twoPagePdf();
        Path overflow = temp.resolve("over.docx");
        var longPage = page("a", 1, "第一行\n第二行\n第三行", TWO_PER_PAGE);
        assertEquals("LAYOUT_OVERFLOW", assertThrows(CaseException.class, () ->
                writer.write(snapshot(List.of(longPage)), Map.of("a", source), overflow)).code());
        assertFalse(Files.exists(overflow));
        var missing = page("a", 3, "错误页码", TWO_PER_PAGE);
        assertEquals("PAGE_UNRENDERABLE", assertThrows(CaseException.class, () ->
                writer.write(snapshot(List.of(missing)), Map.of("a", source), overflow)).code());
        assertFalse(Files.exists(overflow));
        var valid = page("a", 1, "票".repeat(90), TWO_PER_PAGE);
        assertEquals(2, AttachmentLayout.captionLines(valid).size());
        writer.write(snapshot(List.of(valid)), Map.of("a", source), overflow);
        writer.verify(snapshot(List.of(valid)), overflow);
        assertEquals("SOURCE_CHANGED", assertThrows(CaseException.class,
                () -> AttachmentLayout.paginate(List.of(valid, valid))).code());
    }

    private static ExportSnapshot snapshot(List<ExportSnapshot.AttachmentPage> pages) {
        return new ExportSnapshot("case", "样本", "/case", CaseType.TRAVEL, 1, TemplateArchive.BUNDLED_SHA256,
                "1", List.of(), pages, List.of());
    }

    private static void assertPageNumberFooter(Path output, int expectedPages) throws IOException {
        try (var input = Files.newInputStream(output); var document = new XWPFDocument(input)) {
            var footer = document.getHeaderFooterPolicy().getDefaultFooter();
            assertNotNull(footer);
            var paragraph = footer.getParagraphs().getFirst();
            assertEquals(ParagraphAlignment.CENTER, paragraph.getAlignment());
            var runs = paragraph.getRuns();
            assertEquals(List.of("PAGE", "NUMPAGES"), runs.stream().flatMap(run -> run.getCTR().getInstrTextList().stream())
                    .map(instruction -> instruction.getStringValue().strip()).toList());
            assertEquals("第 1/" + expectedPages + " 页", paragraph.getText());
            assertEquals(expectedPages, document.getTables().size());
            assertEquals(expectedPages - 1L, document.getParagraphs().stream().filter(XWPFParagraph::isPageBreak).count());
        }
    }

    private static ExportSnapshot.AttachmentPage page(String id, int number, String caption, ExportSnapshot.Layout layout) {
        return new ExportSnapshot.AttachmentPage(id, "sha", id + ".pdf", number,
                List.of("receipt-a", "receipt-b"), Role.SUPPORT, caption, layout);
    }

    private Path twoPagePdf() throws IOException {
        Path file = temp.resolve("multi.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(144, 90)));
            document.addPage(new PDPage(new PDRectangle(90, 144)));
            document.save(file.toFile());
        }
        return file;
    }

    private Path markedPdf() throws IOException {
        Path file = temp.resolve("marked.pdf");
        try (var document = new PDDocument()) {
            for (int height : List.of(22, 58)) {
                var page = new PDPage(new PDRectangle(144, 180));
                document.addPage(page);
                try (var canvas = new PDPageContentStream(document, page)) {
                    canvas.addRect(36, 72, 72, height);
                    canvas.fill();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private static void assertEmbeddedPixels(BufferedImage expected, XWPFDocument doc, int table, double maxHeight)
            throws IOException {
        var image = doc.getTables().get(table).getRow(0).getCell(0).getParagraphArray(0)
                .getRuns().getFirst().getEmbeddedPictures().getFirst();
        var pixels = ImageIO.read(new ByteArrayInputStream(image.getPictureData().getData()));
        int width = expected.getWidth(), height = expected.getHeight();
        assertEquals(width, pixels.getWidth());
        assertEquals(height, pixels.getHeight());
        assertArrayEquals(expected.getRGB(0, 0, width, height, null, 0, width),
                pixels.getRGB(0, 0, width, height, null, 0, width));
        var extent = image.getCTPicture().getSpPr().getXfrm().getExt();
        double ratio = Math.min(AttachmentLayout.SETTINGS.imageWidthMm() / width, maxHeight / height);
        assertEquals(AttachmentLayout.emu(width * ratio), extent.getCx());
        assertEquals(AttachmentLayout.emu(height * ratio), extent.getCy());
    }
}
