package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.output.ExportSnapshot.ExpenseRow;
import org.apache.poi.ss.format.CellFormat;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
            int extra = count - layout.capacity();
            var snapshot = snapshot(type, entries);
            Path target = scratch.resolve("输出-" + count + ".xlsx");
            writer.write(snapshot, target);
            writer.evaluateAndVerify(snapshot, target);
            assertSignature(target, type, extra, null);
            var named = snapshot(type, entries, "测试·报销人");
            Path signed = scratch.resolve("签字-" + count + ".xlsx");
            writer.write(named, signed);
            writer.evaluateAndVerify(named, signed);
            assertSignature(signed, type, extra, named.reimburser());
            assertTemplateLayout(target, snapshot, layout, extra);
        }
    }

    private static void assertTemplateLayout(Path target, ExportSnapshot snapshot, TemplateCatalog.Layout layout,
                                             int extra) throws IOException {
        CaseType type = snapshot.type();
        int count = snapshot.rows().size();
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
            assertEquals(type == CaseType.TRAVEL ? "m\"月\"d\"日\"" : "yyyy-mm-dd",
                    firstDate.getCellStyle().getDataFormatString());
            var lastColumn = org.apache.poi.ss.util.CellReference.convertNumToColString(layout.columns() - 1);
            assertTrue(book.getPrintArea(0).endsWith("!$A$1:$" + lastColumn + "$" + (layout.printLastRow() + extra)));
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

    private static void assertSignature(Path file, CaseType type, int extra, String name) throws IOException {
        var positions = CellRangeAddress.valueOf(switch (type) {
            case TRAVEL -> "B13:C13";
            case ENTERTAINMENT -> "B10";
            case PRIVATE_CAR -> "B11:C11";
            case TAXI -> "B14:C14";
            case OTHER -> "A12:B12";
        });
        try (var input = Files.newInputStream(file); var book = new XSSFWorkbook(input)) {
            var signature = book.getSheetAt(0).getRow(positions.getFirstRow() + extra);
            var label = signature.getCell(positions.getFirstColumn());
            assertEquals(CellType.STRING, label.getCellType());
            String expected = type == CaseType.ENTERTAINMENT && name != null ? "报销人：" + name : "报销人：";
            assertEquals(expected, label.getStringCellValue());
            if (type == CaseType.ENTERTAINMENT) {
                assertEquals("部门领导：", signature.getCell(2).getStringCellValue());
            } else {
                var cell = signature.getCell(positions.getLastColumn());
                if (name == null) {
                    assertTrue(cell == null || cell.getCellType() == CellType.BLANK);
                } else {
                    assertEquals(CellType.STRING, cell.getCellType());
                    assertEquals(name, cell.getStringCellValue());
                }
            }
        }
    }

    @Test
    void wrapsLongReimburserUsingTheSignatureStyle() throws Exception {
        var snapshot = snapshot(CaseType.ENTERTAINMENT, List.of(), "阿依古丽·阿不都热合曼".repeat(3));
        Path output = scratch.resolve("长姓名.xlsx");
        writer.write(snapshot, output);
        writer.evaluateAndVerify(snapshot, output);
        assertSignature(output, CaseType.ENTERTAINMENT, 0, snapshot.reimburser());
        try (var input = Files.newInputStream(output); var book = new XSSFWorkbook(input);
             var original = Files.newInputStream(archive.locate(TemplateArchive.BUNDLED_SHA256));
             var originalBook = new XSSFWorkbook(original)) {
            var signature = book.getSheetAt(0).getRow(9);
            var originalSignature = originalBook.getSheet(TemplateCatalog.companySheetName(CaseType.ENTERTAINMENT)).getRow(9);
            var style = signature.getCell(1).getCellStyle();
            assertTrue(style.getWrapText());
            assertFalse(style.getShrinkToFit());
            assertEquals(originalSignature.getCell(1).getCellStyle().getFontIndex(), style.getFontIndex());
            assertTrue(signature.getHeightInPoints() > originalSignature.getHeightInPoints());
        }
    }

    @Test
    void rejectsChangesToTheReimburserAndOtherSignatures() throws Exception {
        var snapshot = snapshot(CaseType.TRAVEL, List.of(), "=1+1");
        Path correct = scratch.resolve("公式外观姓名.xlsx");
        writer.write(snapshot, correct);
        writer.evaluateAndVerify(snapshot, correct);
        assertSignature(correct, CaseType.TRAVEL, 0, snapshot.reimburser());

        Path modifiedName = scratch.resolve("改动姓名.xlsx");
        alterSignature(correct, modifiedName, 2, "其他姓名");
        assertTrue(assertThrows(IOException.class, () -> writer.evaluateAndVerify(snapshot, modifiedName))
                .getMessage().contains("C13"));
        assertTrue(assertThrows(IOException.class, () -> writer.evaluateAndVerify(snapshot(CaseType.TRAVEL, List.of()), correct))
                .getMessage().contains("C13"));

        Path modifiedApproval = scratch.resolve("改动审批栏.xlsx");
        alterSignature(correct, modifiedApproval, 4, "其他签字栏");
        assertTrue(assertThrows(IOException.class, () -> writer.evaluateAndVerify(snapshot, modifiedApproval))
                .getMessage().contains("E13"));
    }

    @Test
    void rejectsAnOccupiedOrNonAnchorReimburserCellInTheTemplate() throws Exception {
        var layout = TemplateCatalog.companyLayout(CaseType.TRAVEL);
        try (var input = Files.newInputStream(archive.locate(TemplateArchive.BUNDLED_SHA256));
             var book = new XSSFWorkbook(input)) {
            var sheet = book.getSheet(layout.sheetName());
            var row = sheet.getRow(12);
            var occupied = row.createCell(2);
            occupied.setCellValue("已有说明");
            var occupiedError = assertThrows(CaseException.class, () -> TemplateCatalog.verifyTemplate(book, layout));
            assertEquals("TEMPLATE_MISMATCH", occupiedError.code());
            assertTrue(occupiedError.getMessage().contains("C13"));
            row.removeCell(occupied);
            sheet.addMergedRegion(CellRangeAddress.valueOf("B13:C13"));
            var mergedError = assertThrows(CaseException.class, () -> TemplateCatalog.verifyTemplate(book, layout));
            assertEquals("TEMPLATE_MISMATCH", mergedError.code());
            assertTrue(mergedError.getMessage().contains("C13"));
        }
    }

    private static void alterSignature(Path source, Path target, int column, String value) throws IOException {
        try (var input = Files.newInputStream(source); var book = new XSSFWorkbook(input);
             var output = Files.newOutputStream(target)) {
            book.getSheetAt(0).getRow(12).getCell(column).setCellValue(value);
            book.write(output);
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
            var dateCell = line.getCell(0);
            assertEquals(CellType.NUMERIC, dateCell.getCellType());
            assertEquals(LocalDate.of(2026, 11, 12), dateCell.getLocalDateTimeCellValue().toLocalDate());
            assertEquals("11月12日",
                    CellFormat.getInstance(Locale.CHINA, dateCell.getCellStyle().getDataFormatString()).apply(dateCell).text);
            assertEquals(CellType.BLANK, line.getCell(3).getCellType());
            assertEquals(2, line.getCell(4).getNumericCellValue());
            assertEquals(131, line.getCell(6).getNumericCellValue());
            assertEquals(CellType.BLANK, line.getCell(7).getCellType());
            assertEquals(131, line.getCell(10).getNumericCellValue());
            var total = sheet.getRow(10);
            assertEquals(2, total.getCell(4).getNumericCellValue());
            assertEquals(131, total.getCell(10).getNumericCellValue());
        }
    }

    @Test
    void travelKeepsDateAndRouteIndependentAndWritesConfirmedAmounts() throws Exception {
        var start = LocalDate.of(2026, 11, 3);
        var snapshot = snapshot(CaseType.TRAVEL, List.of(
                new ExpenseRow("date-only", "TRAVEL_SUMMARY", start, null, "差旅", Map.of(), Map.of("TRANSPORT", 79600L)),
                new ExpenseRow("route-only", "TRAVEL_SUMMARY", null, start.plusDays(1), "差旅",
                        Map.of("route", "深圳-宁波"), Map.of("TRANSPORT", 0L)),
                new ExpenseRow("unlocated", "TRAVEL_SUMMARY", null, null, "差旅", Map.of(), Map.of("HOTEL", 50000L))));
        Path target = scratch.resolve("差旅-行程待核对.xlsx");
        writer.write(snapshot, target);
        writer.evaluateAndVerify(snapshot, target);
        try (var stream = Files.newInputStream(target); var book = new XSSFWorkbook(stream)) {
            var sheet = book.getSheetAt(0);
            var dateOnly = sheet.getRow(2);
            var dateCell = dateOnly.getCell(0);
            assertEquals(start, dateCell.getLocalDateTimeCellValue().toLocalDate());
            assertEquals("11月3日",
                    CellFormat.getInstance(Locale.CHINA, dateCell.getCellStyle().getDataFormatString()).apply(dateCell).text);
            assertEquals(CellType.BLANK, dateOnly.getCell(1).getCellType());
            assertEquals(796, dateOnly.getCell(3).getNumericCellValue());
            var routeOnly = sheet.getRow(3);
            assertEquals(CellType.BLANK, routeOnly.getCell(0).getCellType());
            assertEquals("深圳-宁波", routeOnly.getCell(1).getStringCellValue());
            assertEquals(CellType.NUMERIC, routeOnly.getCell(3).getCellType());
            assertEquals(0, routeOnly.getCell(3).getNumericCellValue());
            assertEquals(CellType.NUMERIC, routeOnly.getCell(10).getCachedFormulaResultType());
            assertEquals(0, routeOnly.getCell(10).getNumericCellValue());
            var unlocated = sheet.getRow(4);
            assertEquals(CellType.BLANK, unlocated.getCell(0).getCellType());
            assertEquals(CellType.BLANK, unlocated.getCell(1).getCellType());
            assertEquals(CellType.BLANK, unlocated.getCell(3).getCellType());
            assertEquals(500, unlocated.getCell(5).getNumericCellValue());
            assertEquals(1296, sheet.getRow(10).getCell(10).getNumericCellValue());
        }
    }

    @Test
    void rejectsAnIncorrectTravelDateFormatEvenWhenTheNumericDateMatches() throws Exception {
        var snapshot = snapshot(CaseType.TRAVEL, List.of(row(CaseType.TRAVEL).withAmount(100L)));
        Path correct = scratch.resolve("差旅-日期格式.xlsx");
        Path altered = scratch.resolve("差旅-日期格式不符.xlsx");
        writer.write(snapshot, correct);
        try (var stream = Files.newInputStream(correct); var book = new XSSFWorkbook(stream);
             var output = Files.newOutputStream(altered)) {
            var date = book.getSheetAt(0).getRow(2).getCell(0);
            var style = book.createCellStyle();
            style.cloneStyleFrom(date.getCellStyle());
            style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(style);
            book.write(output);
        }
        var error = assertThrows(IOException.class, () -> writer.evaluateAndVerify(snapshot, altered));
        assertTrue(error.getMessage().contains("差旅日期格式不符 A3"));
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
            assertEquals("2026-11-12\n2026-11-13", detail.getCell(0).getStringCellValue());
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
        return snapshot(type, rows, null);
    }

    private ExportSnapshot snapshot(CaseType type, List<ExpenseRow> rows, String name) {
        return new ExportSnapshot("xlsx", "样本", scratch.toString(), type, 1,
                TemplateArchive.BUNDLED_SHA256, "1", rows, List.of(), List.of(), null, name);
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
