package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.intake.TypeInference.CaseType;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TemplateCatalog {
    private static final Map<CaseType, Layout> COMPANY = Map.of(
            CaseType.TRAVEL, new Layout("1、差旅费明细表-空白", "差旅费用明细表", 3, 11, 13, 13, 11,
                    0, 10, true, List.of("起讫日期", "起讫地点", "交通工具", "交通金额", "住宿天数",
                    "住宿金额", "的士费用", "交通补贴", "伙食补贴", "未住宿补贴", "合计"),
                    Map.of(3, "TRANSPORT", 5, "HOTEL", 6, "TAXI", 7, "TRAFFIC_SUBSIDY", 8, "MEAL_SUBSIDY", 9, "NO_STAY_SUBSIDY"), 4),
            CaseType.ENTERTAINMENT, new Layout("2、业务招待费明细表", "业务招待费明细表", 3, 8, 10, 10, 6,
                    3, 4, true, List.of("时间", "客户单位", "任务完成情况（或沟通什么事项）", "采购/支付明细", "金额", "备注"),
                    Map.of(4, "ENTERTAINMENT"), -1),
            CaseType.PRIVATE_CAR, new Layout("3、私车公用报销明细表", "私车公用报销明细表", 4, 9, 11, 11, 9,
                    1, 8, false, List.of("时间", "出差事由", "起点", "终点", "公里数", "油费", "过路费", "停车费", "合计"),
                    Map.of(5, "FUEL", 6, "TOLL", 7, "PARKING"), 4),
            CaseType.TAXI, new Layout("4、的士费用明细表", "的士费用明细表", 4, 12, 14, 15, 10,
                    2, 5, true, List.of("时间", "", "区间", "人数", "", "费用（元）", "", "", "事由描述", ""),
                    Map.of(5, "TAXI"), -1),
            CaseType.OTHER, new Layout("5、其他费用明细表", "其他费用明细表", 4, 10, 12, 12, 5,
                    0, 3, false, List.of("时间", "地点", "事由", "金额", "备注"), Map.of(3, "OTHER"), -1));

    record Layout(String sheetName, String title, int firstRow, int totalRow, int signatureRow, int printLastRow,
                  int columns, int totalLabelColumn, int totalColumn, boolean landscape, List<String> headings,
                  Map<Integer, String> moneyColumns, int quantityTotalColumn) {
        int capacity() { return totalRow - firstRow; }
        boolean hasRowTotal() { return !moneyColumns.containsKey(totalColumn); }
    }

    record Resolved(Path file, Layout layout) {}

    private final TemplateArchive archive;
    private final Map<String, Map<CaseType, Layout>> versions;

    public TemplateCatalog(TemplateArchive archive) {
        this(archive, Map.of(TemplateArchive.BUNDLED_SHA256, COMPANY));
    }

    TemplateCatalog(TemplateArchive archive, Map<String, Map<CaseType, Layout>> versions) {
        this.archive = archive;
        this.versions = Map.copyOf(versions);
    }

    public Path resolveByHash(String hash, CaseType type) {
        return resolve(hash, type).file();
    }

    Resolved resolve(String hash, CaseType type) {
        var version = hash == null ? null : versions.get(hash);
        Layout layout = version == null || type == null ? null : version.get(type);
        if (layout == null) {
            throw mismatch("没有对应的模板布局 " + hash + " / " + type);
        }
        try {
            return new Resolved(archive.locate(hash), layout);
        } catch (TemplateArchive.TemplateMismatch error) {
            throw new CaseException(error.code(), error.getMessage(), error);
        }
    }

    static Layout companyLayout(CaseType type) {
        return COMPANY.get(type);
    }

    public static String companySheetName(CaseType type) {
        return type == null ? null : companyLayout(type).sheetName();
    }

    static XSSFSheet verifyTemplate(XSSFWorkbook book, Layout layout) {
        var sheet = book.getSheet(layout.sheetName());
        if (sheet == null) {
            throw mismatch("缺少工作表 " + layout.sheetName());
        }
        textAnchor(sheet, 1, 0, layout.title());
        for (int column = 0; column < layout.headings().size(); column++) {
            String heading = layout.headings().get(column);
            if (!heading.isEmpty()) {
                textAnchor(sheet, layout.firstRow() - 1, column, heading);
            }
        }
        textAnchor(sheet, layout.totalRow(), layout.totalLabelColumn(), layout.sheetName().startsWith("5、") ? "总计" : "合计");
        var amount = sheet.getRow(layout.totalRow() - 1).getCell(layout.totalColumn());
        if (amount == null || amount.getCellType() != CellType.FORMULA) {
            throw mismatch("合计公式锚点不符 " + layout.sheetName());
        }
        if (sheet.getPrintSetup().getLandscape() != layout.landscape()
                || sheet.getPrintSetup().getPaperSize() != 9) {
            throw mismatch("模板纸张设置不符 " + layout.sheetName());
        }
        String printArea = book.getPrintArea(book.getSheetIndex(sheet));
        var wanted = "$A$1:$" + (char) ('A' + layout.columns() - 1) + "$" + layout.printLastRow();
        if (printArea == null || !printArea.endsWith("!" + wanted)) {
            throw mismatch("模板打印范围不符 " + layout.sheetName());
        }
        for (int rowIndex = layout.firstRow(); rowIndex <= layout.printLastRow(); rowIndex++) {
            if (sheet.getRow(rowIndex - 1) == null) {
                throw mismatch(layout.sheetName() + " 缺少第 " + rowIndex + " 行");
            }
        }
        return sheet;
    }

    private static void textAnchor(XSSFSheet sheet, int rowNumber, int column, String expected) {
        var row = sheet.getRow(rowNumber - 1);
        var cell = row == null ? null : row.getCell(column);
        if (cell == null || cell.getCellType() != CellType.STRING || !expected.equals(cell.getStringCellValue().strip())) {
            throw mismatch(sheet.getSheetName() + "!" + (char) ('A' + column) + rowNumber + " 应为 " + expected);
        }
    }

    private static CaseException mismatch(String message) {
        return new CaseException("TEMPLATE_MISMATCH", message);
    }
}
