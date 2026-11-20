package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.output.ExportSnapshot.ExpenseRow;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ExcelWriterTest {
    @TempDir Path scratch;
    private TemplateArchive archive;
    private ExcelWriter writer;

    @BeforeEach
    void setup() {
        archive = new TemplateArchive(scratch);
        archive.installBundled();
        writer = new ExcelWriter(new TemplateCatalog(archive));
    }

    @ParameterizedTest
    @EnumSource(CaseType.class)
    void preservesBlankTemplateStyleAndExpandsOneRow(CaseType type) throws Exception {
        var layout = TemplateCatalog.companyLayout(type);
        for (int count : new int[]{layout.capacity(), layout.capacity() + 1}) {
            var entries = IntStream.range(0, count).mapToObj(i -> row(type).withAmount(100L + i)).toList();
            var snapshot = snapshot(type, entries);
            Path target = scratch.resolve("输出-" + count + ".xlsx");
            writer.write(snapshot, target);
            writer.evaluateAndVerify(snapshot, target);
            int extra = count - layout.capacity();
            try (var input = Files.newInputStream(target); var book = new XSSFWorkbook(input)) {
                var sheet = book.getSheetAt(0);
                assertEquals(1, book.getNumberOfSheets());
                assertEquals(layout.sheetName(), sheet.getSheetName());
                assertEquals(layout.landscape(), sheet.getPrintSetup().getLandscape());
                assertEquals(9, sheet.getPrintSetup().getPaperSize());
                assertEquals(layout.firstRow() - 2, sheet.getRepeatingRows().getLastRow());
                assertEquals(1, sheet.getPrintSetup().getFitWidth());
                var firstDate = sheet.getRow(layout.firstRow() - 1).getCell(0);
                assertTrue(DateUtil.isCellDateFormatted(firstDate));
                var total = sheet.getRow(layout.totalRow() + extra - 1).getCell(layout.totalColumn());
                assertEquals(IntStream.range(0, count).mapToDouble(i -> (100 + i) / 100.0).sum(),
                        total.getNumericCellValue(), 0.00001);
                if (type == CaseType.TAXI) {
                    int lastDetail = layout.totalRow() + extra - 1;
                    var ranges = sheet.getMergedRegions().stream().map(org.apache.poi.ss.util.CellRangeAddress::formatAsString).toList();
                    assertTrue(ranges.contains("F" + lastDetail + ":H" + lastDetail));
                    assertTrue(ranges.contains("I" + lastDetail + ":J" + lastDetail));
                    for (int col : new int[]{1, 4, 6, 7, 9}) {
                        var cell = sheet.getRow(lastDetail - 1).getCell(col);
                        assertTrue(cell == null || cell.getCellType() == CellType.BLANK);
                    }
                }
            }
        }
    }

    @Test
    void confirmsBlankZeroPartialAndTaxiComponentWithoutCountingNights() throws Exception {
        var empty = snapshot(CaseType.OTHER,
                List.of(row(CaseType.OTHER).withAmount(null), row(CaseType.OTHER).withAmount(null)));
        Path emptyFile = scratch.resolve("其他-待核对.xlsx");
        writer.write(empty, emptyFile);
        writer.evaluateAndVerify(empty, emptyFile);
        var zero = snapshot(CaseType.OTHER, List.of(row(CaseType.OTHER).withAmount(0L), row(CaseType.OTHER).withAmount(null)));
        Path zeroFile = scratch.resolve("其他-已确认零.xlsx");
        writer.write(zero, zeroFile);
        writer.evaluateAndVerify(zero, zeroFile);
        try (var stream = Files.newInputStream(zeroFile); var book = new XSSFWorkbook(stream)) {
            var sheet = book.getSheetAt(0);
            assertEquals(CellType.BLANK, sheet.getRow(4).getCell(3).getCellType());
            var total = sheet.getRow(9).getCell(3);
            assertEquals(CellType.NUMERIC, total.getCachedFormulaResultType());
            assertEquals("0.00", new DataFormatter().formatCellValue(total, book.getCreationHelper().createFormulaEvaluator()));
        }
        var row = new ExpenseRow("travel", "RIDE", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 13),
                "网约车", Map.of("nights", "2"), Map.of("TAXI", 13100L));
        var partial = snapshot(CaseType.TRAVEL, List.of(row));
        Path partialFile = scratch.resolve("差旅-部分确认.xlsx");
        writer.write(partial, partialFile);
        writer.evaluateAndVerify(partial, partialFile);
        try (var stream = Files.newInputStream(partialFile); var book = new XSSFWorkbook(stream)) {
            var sheet = book.getSheetAt(0);
            var line = sheet.getRow(2);
            assertEquals("2026-11-12\n2026-11-13", line.getCell(0).getStringCellValue());
            assertTrue(line.getHeightInPoints() > 20);
            assertEquals(CellType.BLANK, line.getCell(3).getCellType());
            assertEquals(2, line.getCell(4).getNumericCellValue());
            assertEquals(131, line.getCell(6).getNumericCellValue());
            assertEquals(CellType.BLANK, line.getCell(7).getCellType());
            assertEquals(131, sheet.getRow(10).getCell(10).getNumericCellValue());
        }
    }

    @Test
    void expandsLongTaxiNarrativeAndRejectsUnprintableText() throws Exception {
        String narrative = "差旅开始" + "出差事由和路线需要完整保留".repeat(12) + "路线结束";
        var row = new ExpenseRow("long", "DRIVER", null, null, narrative, Map.of(), Map.of("TAXI", 13100L));
        var snapshot = snapshot(CaseType.TAXI, List.of(row));
        Path target = scratch.resolve("长说明.xlsx");
        writer.write(snapshot, target);
        writer.evaluateAndVerify(snapshot, target);
        try (var stream = Files.newInputStream(target); var book = new XSSFWorkbook(stream)) {
            var sheet = book.getSheetAt(0);
            assertTrue(sheet.getRow(3).getHeightInPoints() > 30);
            assertEquals(narrative, sheet.getRow(3).getCell(8).getStringCellValue());
        }
        var overflow = new ExpenseRow("overflow", "DRIVER", null, null, "无法容纳".repeat(1000), Map.of(), Map.of());
        Path failed = scratch.resolve("溢出.xlsx");
        var error = assertThrows(CaseException.class, () -> writer.write(snapshot(CaseType.TAXI, List.of(overflow)), failed));
        assertEquals("LAYOUT_OVERFLOW", error.code());
        assertTrue(error.getMessage().contains("I4"));
        assertFalse(Files.exists(failed));
    }

    @ParameterizedTest
    @EnumSource(value = CaseType.class, names = {"TAXI", "OTHER"})
    void keepsTheWpsRegressionNarrativeVisibleInBothNarrowLayouts(CaseType type) throws Exception {
        String narrative = "记录起始：赴客户现场核对设备清单并确认后续交接时间，按要求携带资料依次完成现场盘点、出入核验和联系人确认。"
                + "当晚返回，次日补充缺项说明并将纸质资料交回。记录末尾。";
        boolean taxi = type == CaseType.TAXI;
        LocalDate date = LocalDate.of(2026, 11, 12);
        var row = new ExpenseRow("visual-probe", "示例", date, date.plusDays(1), taxi ? narrative : "受控其他费用",
                taxi ? Map.of("route", "机场 → 客户地点 → 车站", "people", "2") : Map.of("note", narrative),
                taxi ? Map.of("TAXI", 13100L) : Map.of());
        var snapshot = snapshot(type, List.of(row));
        var target = scratch.resolve(type + "-备注回归.xlsx");
        writer.write(snapshot, target);
        writer.evaluateAndVerify(snapshot, target);
        try (var stream = Files.newInputStream(target); var book = new XSSFWorkbook(stream)) {
            var detail = book.getSheetAt(0).getRow(3);
            var cell = detail.getCell(taxi ? 8 : 4);
            assertEquals(narrative, cell.getStringCellValue());
            assertEquals(taxi ? "宋体" : "微软雅黑", cell.getCellStyle().getFont().getFontName());
            assertEquals(10, cell.getCellStyle().getFont().getFontHeightInPoints());
            assertTrue(detail.getHeightInPoints() >= 155, () -> "WPS 行高不足 " + detail.getHeightInPoints());
        }
    }

    @Test
    void resolvesExplicitTemplateVersionsAndRejectsUnknownOrWrongTemplate() throws Exception {
        Path original = archive.locate(TemplateArchive.BUNDLED_SHA256);
        Path alternate = scratch.resolve("验证用历史模板.xlsx");
        try (var stream = Files.newInputStream(original); var workbook = new XSSFWorkbook(stream);
             var output = Files.newOutputStream(alternate)) {
            workbook.getProperties().getCoreProperties().setTitle("另一版模板");
            workbook.write(output);
        }
        String alternateHash = TemplateArchive.sha256(alternate);
        assertNotEquals(TemplateArchive.BUNDLED_SHA256, alternateHash);
        Path historic = archive.locate(alternateHash, alternate);
        var layout = TemplateCatalog.companyLayout(CaseType.TRAVEL);
        var selected = new TemplateCatalog(archive, Map.of(
                TemplateArchive.BUNDLED_SHA256, Map.of(CaseType.TRAVEL, layout),
                alternateHash, Map.of(CaseType.TRAVEL, layout)));
        var historicSnapshot = new ExportSnapshot("old", "出差", scratch.toString(), CaseType.TRAVEL, 2,
                alternateHash, "1", List.of(row(CaseType.TRAVEL).withAmount(567L)), List.of(), List.of());
        assertEquals("TEMPLATE_MISMATCH", assertThrows(CaseException.class,
                () -> writer.write(historicSnapshot, scratch.resolve("未知模板.xlsx"))).code());
        var saved = new ExcelWriter(selected);
        var before = scratch.resolve("历史清单.xlsx");
        saved.write(historicSnapshot, before);
        saved.evaluateAndVerify(historicSnapshot, before);
        Files.delete(historic);
        assertEquals("TEMPLATE_MISMATCH", assertThrows(CaseException.class,
                () -> saved.write(historicSnapshot, scratch.resolve("缺失模板.xlsx"))).code());
        assertThrows(TemplateArchive.TemplateMismatch.class, () -> archive.locate(alternateHash, original));
        archive.locate(alternateHash, alternate);
        saved.evaluateAndVerify(historicSnapshot, before);
    }

    @Test
    void usesTheRealWpsProbeRowsAndCorporateSignatures() throws Exception {
        var snapshot = snapshot(CaseType.TRAVEL, List.of(
                new ExpenseRow("toll", "TOLL", LocalDate.of(2026, 11, 3), null, "通行费",
                        Map.of("route", "杭州", "vehicle", "通行费"), Map.of("TRANSPORT", 11390L)),
                new ExpenseRow("parking", "PARKING", LocalDate.of(2026, 11, 3), null, "停车费",
                        Map.of("route", "杭州", "vehicle", "停车费"), Map.of("TRANSPORT", 3000L))));
        var probe = System.getProperty("expenseassistant.excelProbeOutput");
        Path target = probe == null ? scratch.resolve("差旅-正式探针.xlsx") : Path.of(probe);
        writer.write(snapshot, target);
        writer.evaluateAndVerify(snapshot, target);
        try (var stream = Files.newInputStream(target); var book = new XSSFWorkbook(stream)) {
            var sheet = book.getSheetAt(0);
            assertEquals(113.90, sheet.getRow(2).getCell(3).getNumericCellValue());
            assertEquals(30.00, sheet.getRow(3).getCell(3).getNumericCellValue());
            assertEquals(143.90, sheet.getRow(10).getCell(10).getNumericCellValue());
            assertEquals("报销人：", sheet.getRow(12).getCell(1).getStringCellValue());
            assertEquals(0, sheet.getRowBreaks().length);
            assertEquals("伙食补贴", sheet.getRow(1).getCell(8).getStringCellValue());
            assertEquals("未住宿补贴", sheet.getRow(1).getCell(9).getStringCellValue());
            assertTrue(sheet.getRow(1).getHeightInPoints() > 30);
            assertEquals("A2:K10", sheet.getCTWorksheet().getAutoFilter().getRef());
            assertTrue(book.getAllNames().stream().anyMatch(name ->
                    "_xlnm._FilterDatabase".equals(name.getNameName()) && name.getSheetIndex() == 0
                            && name.getRefersToFormula().endsWith("!$A$2:$K$10")));
        }
    }

    private ExportSnapshot snapshot(CaseType type, List<ExpenseRow> rows) {
        return new ExportSnapshot("xlsx", "样本", scratch.toString(), type, 1,
                TemplateArchive.BUNDLED_SHA256, "1", rows, List.of(), List.of());
    }

    private static SampleRow row(CaseType type) {
        var layout = TemplateCatalog.companyLayout(type);
        return new SampleRow(layout.moneyColumns().values().iterator().next());
    }

    private record SampleRow(String component) {
        ExpenseRow withAmount(Long minor) {
            return new ExpenseRow("sample", "样本", LocalDate.of(2026, 11, 3), null, "样本", Map.of(),
                    minor == null ? Map.of() : Map.of(component, minor));
        }
    }
}
