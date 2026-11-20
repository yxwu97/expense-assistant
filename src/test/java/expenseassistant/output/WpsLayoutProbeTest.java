package expenseassistant.output;

import expenseassistant.support.SampleCorpus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WpsLayoutProbeTest {
    private static final String TRAVEL = "1、差旅费明细表-空白";
    private static final String[] CAPTIONS = {
            "2026-11-03 杭州出差 通行费 113.90 元（版式探针）",
            "2026-11-03 杭州出差 停车费 30.00 元（版式探针）"
    };
    @TempDir Path temporary;

    @Test
    void realWorkbookAndTwoInvoices() throws Exception {
        Path original = SampleCorpus.defaultSource();
        assumeTrue(Files.isDirectory(original), "未提供受控报销样本目录 " + original);
        Path batch = temporary.resolve("样本副本");
        var entries = SampleCorpus.verifyAndCopy(original, batch);
        List<Path> invoices = List.of(source(entries, batch, "P07"), source(entries, batch, "P06"));
        var template = new TemplateArchive(temporary).installBundled();
        var layout = new Properties();
        try (var properties = getClass().getResourceAsStream("/output/word-layout.properties")) {
            assertNotNull(properties);
            layout.load(properties);
        }

        String output = System.getProperty("expenseassistant.wpsProbeDirectory");
        Path directory = output == null ? temporary : Path.of(output).toAbsolutePath().normalize();
        SampleCorpus.checkOutsideSource(original, directory);
        Files.createDirectories(directory);
        Path excel = directory.resolve("差旅版式探针.xlsx");
        Path word = directory.resolve("双票版式探针.docx");
        writeExcel(template, excel);
        writeWord(invoices, word, layout);
        verifyExcel(excel);
        verifyWord(word, layout);
    }

    private static Path source(List<SampleCorpus.Entry> entries, Path batch, String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .map(entry -> batch.resolve(entry.relativePath())).orElseThrow();
    }

    private static void writeExcel(Path template, Path target) throws IOException {
        try (var input = Files.newInputStream(template); var workbook = new XSSFWorkbook(input)) {
            for (int index = workbook.getNumberOfSheets() - 1; index >= 0; index--) {
                if (!TRAVEL.equals(workbook.getSheetName(index))) {
                    workbook.removeSheetAt(index);
                }
            }
            workbook.setActiveSheet(0);
            workbook.setSelectedTab(0);
            workbook.setFirstVisibleTab(0);
            workbook.setPrintArea(0, 0, 10, 0, 12);
            var sheet = workbook.getSheetAt(0);
            var date = LocalDate.of(2026, 11, 3);
            for (int rowIndex = 2; rowIndex <= 3; rowIndex++) {
                var row = sheet.getRow(rowIndex);
                row.getCell(0).setCellValue(date);
                row.getCell(1).setCellValue("杭州");
                row.getCell(2).setCellValue(rowIndex == 2 ? "通行费" : "停车费");
                row.getCell(3).setCellValue(rowIndex == 2 ? 113.90 : 30.00);
            }
            for (int rowIndex = 2; rowIndex < 10; rowIndex++) {
                int excelRow = rowIndex + 1;
                sheet.getRow(rowIndex).getCell(10).setCellFormula(String.format(
                        "IF(COUNT(D%d,F%d:J%d)=0,\"\",SUM(D%d,F%d:J%d))",
                        excelRow, excelRow, excelRow, excelRow, excelRow, excelRow));
            }
            sheet.getRow(10).getCell(3).setCellFormula("SUM(D3:D10)");
            sheet.getRow(10).getCell(10).setCellFormula("SUM(K3:K10)");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                workbook.write(output);
            }
        }
    }

    private static void writeWord(List<Path> sources, Path target, Properties layout) throws Exception {
        try (var document = new XWPFDocument()) {
            int pageWidth = twips(number(layout, "page.width.mm"));
            int pageHeight = twips(number(layout, "page.height.mm"));
            int pageMargin = twips(number(layout, "page.margin.mm"));
            int pageBodyWidth = pageWidth - 2 * pageMargin;
            var section = document.getDocument().getBody().addNewSectPr();
            var size = section.addNewPgSz();
            size.setW(BigInteger.valueOf(pageWidth));
            size.setH(BigInteger.valueOf(pageHeight));
            size.setOrient(STPageOrientation.PORTRAIT);
            var margin = section.addNewPgMar();
            margin.setLeft(BigInteger.valueOf(pageMargin));
            margin.setRight(BigInteger.valueOf(pageMargin));
            margin.setTop(BigInteger.valueOf(pageMargin));
            margin.setBottom(BigInteger.valueOf(pageMargin));
            margin.setHeader(BigInteger.ZERO);
            margin.setFooter(BigInteger.ZERO);
            margin.setGutter(BigInteger.ZERO);

            var table = document.createTable(3, 1);
            table.setTableAlignment(TableRowAlign.CENTER);
            table.setWidth(pageBodyWidth);
            table.removeBorders();
            table.setCellMargins(0, 0, 0, 0);
            table.getCTTbl().getTblPr().addNewTblLayout().setType(STTblLayoutType.FIXED);
            table.getCTTbl().addNewTblGrid().addNewGridCol().setW(BigInteger.valueOf(pageBodyWidth));
            for (int index = 0; index < 3; index++) {
                var row = table.getRow(index);
                row.setHeightRule(TableRowHeightRule.EXACT);
                row.setCantSplitRow(true);
                row.setHeight(twips(number(layout, index == 1 ? "slot.gap.mm" : "slot.height.mm")));
                var cell = row.getCell(0);
                cell.setWidth(Integer.toString(pageBodyWidth));
                if (index == 1) {
                    compact(cell.getParagraphArray(0), 1);
                } else {
                    cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
                    addInvoice(cell, sources.get(index / 2), CAPTIONS[index / 2], layout);
                }
            }
            compact(document.createParagraph(), 1);
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                document.write(output);
            }
        }
    }

    private static void addInvoice(XWPFTableCell cell, Path source, String text, Properties layout) throws Exception {
        try (var invoice = Loader.loadPDF(source.toFile()); var bytes = new ByteArrayOutputStream()) {
            var page = new PDFRenderer(invoice).renderImageWithDPI(0, (float) number(layout, "image.dpi"), ImageType.RGB);
            ImageIO.write(page, "png", bytes);
            double ratio = Math.min(number(layout, "image.max.width.mm") / page.getWidth(),
                    number(layout, "image.max.height.mm") / page.getHeight());
            int width = emu(page.getWidth() * ratio);
            int height = emu(page.getHeight() * ratio);
            var picture = cell.getParagraphArray(0);
            picture.setAlignment(ParagraphAlignment.CENTER);
            picture.setSpacingBefore(0);
            picture.setSpacingAfter(0);
            picture.createRun().addPicture(new ByteArrayInputStream(bytes.toByteArray()), PictureType.PNG,
                    "凭证页.png", width, height);
            var caption = cell.addParagraph();
            compact(caption, number(layout, "caption.line.spacing.pt"));
            caption.setAlignment(ParagraphAlignment.CENTER);
            var run = caption.createRun();
            String font = layout.getProperty("caption.font");
            run.setFontFamily(font, XWPFRun.FontCharRange.ascii);
            run.setFontFamily(font, XWPFRun.FontCharRange.hAnsi);
            run.setFontFamily(font, XWPFRun.FontCharRange.eastAsia);
            run.setFontSize(number(layout, "caption.size.pt"));
            run.setText(text);
        }
    }

    private static void compact(XWPFParagraph paragraph, double points) {
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(points, LineSpacingRule.EXACT);
    }

    private static void verifyExcel(Path target) throws IOException {
        try (var input = Files.newInputStream(target); var workbook = new XSSFWorkbook(input)) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals(TRAVEL, workbook.getSheetName(0));
            assertEquals("'" + TRAVEL + "'!$A$1:$K$13", workbook.getPrintArea(0));
            var sheet = workbook.getSheetAt(0);
            assertEquals(113.90, sheet.getRow(2).getCell(3).getNumericCellValue(), 0.0001);
            assertEquals(30.00, sheet.getRow(3).getCell(3).getNumericCellValue(), 0.0001);
            var total = sheet.getRow(10).getCell(10);
            assertEquals(CellType.NUMERIC, total.getCachedFormulaResultType());
            assertEquals(143.90, total.getNumericCellValue(), 0.0001);
        }
    }

    private static void verifyWord(Path target, Properties layout) throws IOException {
        try (var input = Files.newInputStream(target); var document = new XWPFDocument(input)) {
            assertEquals(1, document.getTables().size());
            assertEquals(2, document.getAllPictures().size());
            var section = document.getDocument().getBody().getSectPr();
            assertEquals(BigInteger.valueOf(twips(number(layout, "page.width.mm"))), section.getPgSz().getW());
            assertEquals(BigInteger.valueOf(twips(number(layout, "page.height.mm"))), section.getPgSz().getH());
            assertEquals(STPageOrientation.PORTRAIT, section.getPgSz().getOrient());
            assertEquals(BigInteger.valueOf(twips(number(layout, "page.margin.mm"))), section.getPgMar().getLeft());
            var table = document.getTables().getFirst();
            for (int index = 0; index < CAPTIONS.length; index++) {
                var cell = table.getRow(index * 2).getCell(0);
                assertEquals(1, cell.getParagraphArray(0).getRuns().getFirst().getEmbeddedPictures().size());
                var caption = cell.getParagraphArray(1).getRuns().getFirst();
                assertEquals(CAPTIONS[index], caption.text());
                assertEquals("宋体", caption.getFontFamily(XWPFRun.FontCharRange.eastAsia));
            }
        }
    }

    private static double number(Properties layout, String key) {
        return Double.parseDouble(layout.getProperty(key));
    }

    private static int twips(double millimeters) {
        return (int) Math.floor(millimeters * 1440 / 25.4);
    }

    private static int emu(double millimeters) {
        return (int) Math.floor(millimeters * 36_000);
    }
}
