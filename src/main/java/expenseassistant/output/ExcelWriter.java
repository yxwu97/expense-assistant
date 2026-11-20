package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.intake.TypeInference.CaseType;
import expenseassistant.output.ExportSnapshot.ExpenseRow;
import expenseassistant.output.TemplateCatalog.Layout;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExcelWriter {
    private static final String DATE_FORMAT = "yyyy-mm-dd";
    private static final String AMOUNT_FORMAT = "0.00";
    private static final int PRIVATE_CAR_DATE_WIDTH = 14 * 256;
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);
    private static final float EXCEL_ROW_LIMIT = 409;
    private final TemplateCatalog templates;

    public ExcelWriter(TemplateCatalog templates) {
        this.templates = templates;
    }

    public void write(ExportSnapshot snapshot, Path target) throws IOException {
        var template = templates.resolve(snapshot.templateHash(), snapshot.type());
        Layout layout = template.layout();
        try (var stream = Files.newInputStream(template.file()); var book = new XSSFWorkbook(stream)) {
            XSSFSheet sheet = TemplateCatalog.verifyTemplate(book, layout);
            retainSheet(book, sheet);
            if (snapshot.type() == CaseType.PRIVATE_CAR) {
                sheet.setColumnWidth(0, Math.max(sheet.getColumnWidth(0), PRIVATE_CAR_DATE_WIDTH));
            }
            int extra = Math.max(0, snapshot.rows().size() - layout.capacity());
            if (sheet.getLastRowNum() + (long) extra >= 1_048_576) {
                throw new CaseException("LAYOUT_OVERFLOW", "报销行数超过 Excel 上限");
            }
            expandRows(sheet, layout, extra);
            int total = layout.totalRow() + extra;
            var styles = new CellStyles(book);
            int heading = layout.firstRow() - 2;
            measureHeadings(sheet, layout, styles, heading);
            var widths = textWidths(sheet, layout, layout.firstRow() - 1);
            for (int row = layout.firstRow(); row < total; row++) {
                int index = row - layout.firstRow();
                var values = index < snapshot.rows().size()
                        ? project(snapshot.type(), snapshot.rows().get(index), layout) : Map.<Integer, Object>of();
                writeDetail(snapshot.type(), sheet, layout, styles, row, values, widths);
            }
            writeTotals(sheet, layout, styles, total);
            paginate(book, sheet, layout, total, extra);
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.setForceFormulaRecalculation(false);
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                book.write(output);
            }
        }
    }

    public void evaluateAndVerify(ExportSnapshot snapshot, Path output) throws IOException {
        var source = templates.resolve(snapshot.templateHash(), snapshot.type());
        var layout = source.layout();
        int extra = Math.max(0, snapshot.rows().size() - layout.capacity());
        try (var originalStream = Files.newInputStream(source.file());
             var original = new XSSFWorkbook(originalStream);
             var stream = Files.newInputStream(output);
             var book = new XSSFWorkbook(stream)) {
            XSSFSheet sourceSheet = TemplateCatalog.verifyTemplate(original, layout);
            require(book.getNumberOfSheets() == 1 && book.getActiveSheetIndex() == 0
                    && book.getSheetVisibility(0) == SheetVisibility.VISIBLE
                    && layout.sheetName().equals(book.getSheetName(0)), "成品工作表不符");
            XSSFSheet sheet = book.getSheetAt(0);
            require(sheet.getPrintSetup().getPaperSize() == sourceSheet.getPrintSetup().getPaperSize()
                    && sheet.getPrintSetup().getLandscape() == layout.landscape(), "成品纸型或方向不符");
            var printArea = book.getPrintArea(0);
            require(printArea != null && printArea.endsWith("!$A$1:$" + column(layout.columns() - 1)
                    + "$" + (layout.printLastRow() + extra)), "成品打印范围不符");
            require(regions(sheet.getMergedRegions()).equals(regions(expandedMerges(sourceSheet.getMergedRegions(), layout, extra))),
                    "合并区域与模板不符");
            if (sourceSheet.getCTWorksheet().isSetAutoFilter()) {
                int firstHeading = layout.firstRow() - 1;
                int lastDetail = layout.totalRow() + extra - 1;
                require(sheet.getCTWorksheet().isSetAutoFilter()
                        && ("A" + firstHeading + ":" + column(layout.columns() - 1) + lastDetail)
                        .equals(sheet.getCTWorksheet().getAutoFilter().getRef()), "筛选范围不符");
            }
            var signature = sourceSheet.getRow(layout.signatureRow() - 1);
            for (var candidate : signature) {
                if (candidate.getCellType() == CellType.STRING) {
                    var written = sheet.getRow(layout.signatureRow() + extra - 1).getCell(candidate.getColumnIndex());
                    require(written != null && written.getCellType() == CellType.STRING
                            && candidate.getStringCellValue().equals(written.getStringCellValue()), "成品签字区域不符");
                }
            }
            int totalRow = layout.totalRow() + extra;
            var totals = new HashMap<Integer, BigDecimal>();
            for (int row = layout.firstRow(); row < totalRow; row++) {
                int index = row - layout.firstRow();
                var values = index < snapshot.rows().size()
                        ? project(snapshot.type(), snapshot.rows().get(index), layout) : Map.<Integer, Object>of();
                BigDecimal rowTotal = null;
                for (int col : writeColumns(snapshot.type(), layout)) {
                    XSSFCell cell = sheet.getRow(row - 1).getCell(col);
                    if (layout.hasRowTotal() && col == layout.totalColumn()) {
                        continue;
                    }
                    Object expected = values.get(col);
                    require(matches(cell, expected), "单元格与草稿不符 " + column(col) + row);
                    if (expected instanceof BigDecimal amount && (layout.moneyColumns().containsKey(col)
                            || col == layout.quantityTotalColumn())) {
                        totals.merge(col, amount, BigDecimal::add);
                        if (layout.moneyColumns().containsKey(col)) {
                            rowTotal = rowTotal == null ? amount : rowTotal.add(amount);
                        }
                    }
                }
                if (layout.hasRowTotal()) {
                    verifyFormula(sheet, row, layout.totalColumn(), rowTotal, rowFormula(layout, row));
                    if (rowTotal != null) {
                        totals.merge(layout.totalColumn(), rowTotal, BigDecimal::add);
                    }
                }
            }
            for (int col : sumColumns(layout)) {
                verifyFormula(sheet, totalRow, col, totals.get(col), totalFormula(layout, totalRow, col));
            }
        }
    }

    private static void retainSheet(XSSFWorkbook book, XSSFSheet sheet) {
        for (int index = book.getNumberOfSheets() - 1; index >= 0; index--) {
            if (book.getSheetAt(index) != sheet) {
                book.removeSheetAt(index);
            }
        }
        book.setSheetVisibility(0, SheetVisibility.VISIBLE);
        book.setActiveSheet(0);
        book.setSelectedTab(0);
        book.setFirstVisibleTab(0);
    }

    void expandRows(XSSFSheet sheet, Layout layout, int extra) {
        if (extra == 0) {
            return;
        }
        int pivot = layout.totalRow() - 1;
        var detail = sheet.getRow(pivot - 1);
        var merges = expandedMerges(sheet.getMergedRegions(), layout, extra);
        sheet.removeMergedRegions(java.util.stream.IntStream.range(0, sheet.getNumMergedRegions()).boxed().toList());
        sheet.shiftRows(pivot, sheet.getLastRowNum(), extra, true, false);
        for (int index = pivot; index < pivot + extra; index++) {
            var inserted = sheet.createRow(index);
            inserted.setHeight(detail.getHeight());
            if (detail.getRowStyle() != null) {
                inserted.setRowStyle(detail.getRowStyle());
            }
            for (var cell : detail) {
                inserted.createCell(cell.getColumnIndex(), CellType.BLANK).setCellStyle(cell.getCellStyle());
            }
        }
        merges.forEach(sheet::addMergedRegion);
    }

    private static List<CellRangeAddress> expandedMerges(List<CellRangeAddress> original, Layout layout, int extra) {
        int pivot = layout.totalRow() - 1;
        var result = new ArrayList<CellRangeAddress>();
        for (var merge : original) {
            if (merge.getFirstRow() >= pivot) {
                result.add(shift(merge, extra));
            } else {
                result.add(merge.copy());
                if (merge.getFirstRow() == pivot - 1 && merge.getLastRow() == pivot - 1) {
                    for (int count = 1; count <= extra; count++) {
                        result.add(shift(merge, count));
                    }
                }
            }
        }
        return result;
    }

    private static CellRangeAddress shift(CellRangeAddress range, int rows) {
        return new CellRangeAddress(range.getFirstRow() + rows, range.getLastRow() + rows,
                range.getFirstColumn(), range.getLastColumn());
    }

    private static List<String> regions(List<CellRangeAddress> ranges) {
        return ranges.stream().map(CellRangeAddress::formatAsString).sorted().toList();
    }

    private static Map<Integer, Object> project(CaseType type, ExpenseRow row, Layout layout) {
        var fields = row.fields();
        var values = new LinkedHashMap<Integer, Object>();
        if (row.startDate() != null) {
            values.put(0, row.endDate() == null || row.endDate().equals(row.startDate())
                    ? row.startDate() : row.startDate() + "\n" + row.endDate());
        }
        switch (type) {
            case TRAVEL -> {
                text(values, 1, route(fields));
                text(values, 2, fields.get("vehicle"));
                quantity(values, 4, fields.get("nights"));
            }
            case ENTERTAINMENT -> {
                text(values, 1, fields.get("customer"));
                text(values, 2, fields.get("agenda"));
                text(values, 3, fields.get("detail"));
                text(values, 5, fields.get("note"));
            }
            case PRIVATE_CAR -> {
                text(values, 1, row.description());
                text(values, 2, fields.get("origin"));
                text(values, 3, fields.get("destination"));
                quantity(values, 4, fields.get("distanceKm"));
            }
            case TAXI -> {
                text(values, 2, route(fields));
                quantity(values, 3, fields.get("people"));
                text(values, 8, join(row.description(), fields.get("note"), "\n"));
            }
            case OTHER -> {
                text(values, 1, fields.get("location"));
                text(values, 2, fields.getOrDefault("detail", row.description()));
                text(values, 4, fields.get("note"));
            }
        }
        layout.moneyColumns().forEach((column, component) -> {
            Long minor = row.amounts().get(component);
            if (minor != null) {
                values.put(column, BigDecimal.valueOf(minor, 2));
            }
        });
        return values;
    }

    private static String route(Map<String, String> fields) {
        return fields.getOrDefault("route", join(fields.get("origin"), fields.get("destination"), " → "));
    }

    private static String join(String first, String second, String delimiter) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return second == null || second.isBlank() ? first : first + delimiter + second;
    }

    private static void text(Map<Integer, Object> values, int column, String value) {
        if (value != null && !value.isEmpty()) {
            values.put(column, value);
        }
    }

    private static void quantity(Map<Integer, Object> values, int column, String value) {
        if (value != null && !value.isBlank()) {
            values.put(column, new BigDecimal(value));
        }
    }

    private static int[] writeColumns(CaseType type, Layout layout) {
        return type == CaseType.TAXI ? new int[]{0, 2, 3, 5, 8} : java.util.stream.IntStream.range(0, layout.columns()).toArray();
    }

    private static void writeDetail(CaseType type, XSSFSheet sheet, Layout layout, CellStyles styles,
                                    int rowNumber, Map<Integer, Object> values, float[] widths) {
        var row = sheet.getRow(rowNumber - 1);
        float height = row.getHeightInPoints();
        for (int col : writeColumns(type, layout)) {
            XSSFCell cell = row.getCell(col, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            boolean money = layout.moneyColumns().containsKey(col);
            Object value = values.get(col);
            if (layout.hasRowTotal() && col == layout.totalColumn()) {
                cell.setCellStyle(styles.forCell(cell, AMOUNT_FORMAT, false));
                cell.setCellFormula(rowFormula(layout, rowNumber));
            } else if (value instanceof String text) {
                if (text.length() > 32_767) {
                    throw overflow(sheet, rowNumber, col);
                }
                cell.setCellStyle(styles.forCell(cell, null, true));
                float demanded = requiredHeight(cell, text, widths[col], true);
                if (demanded > EXCEL_ROW_LIMIT) {
                    throw overflow(sheet, rowNumber, col);
                }
                height = Math.max(height, demanded);
                cell.setCellValue(text);
            } else if (value instanceof LocalDate date) {
                cell.setCellStyle(styles.forCell(cell, DATE_FORMAT, false));
                cell.setCellValue(date);
            } else {
                if (money) {
                    cell.setCellStyle(styles.forCell(cell, AMOUNT_FORMAT, false));
                }
                if (value instanceof BigDecimal decimal) {
                    double numeric = decimal.doubleValue();
                    if (!Double.isFinite(numeric) || decimal.stripTrailingZeros().precision() > 15
                            || BigDecimal.valueOf(numeric).compareTo(decimal) != 0) {
                        throw new CaseException("LAYOUT_OVERFLOW", "数值超出 Excel 可保留的精度 " + column(col) + rowNumber);
                    }
                    cell.setCellValue(numeric);
                } else {
                    cell.setBlank();
                }
            }
        }
        row.setHeightInPoints(height);
    }

    private static void measureHeadings(XSSFSheet sheet, Layout layout, CellStyles styles, int index) {
        var header = sheet.getRow(index);
        var widths = textWidths(sheet, layout, index);
        float height = header.getHeightInPoints();
        float filterButton = sheet.getCTWorksheet().isSetAutoFilter() ? 8 : 0;
        for (int col = 0; col < layout.columns(); col++) {
            String text = layout.headings().get(col);
            if (!text.isEmpty()) {
                XSSFCell cell = header.getCell(col);
                if (!cell.getCellStyle().getWrapText()) {
                    cell.setCellStyle(styles.forCell(cell, null, true));
                }
                // Reserve space for a filter button and WPS's header line padding.
                height = Math.max(height, requiredHeight(cell, text, widths[col] - filterButton, false) + 8);
            }
        }
        header.setHeightInPoints(height);
    }

    private static float[] textWidths(XSSFSheet sheet, Layout layout, int row) {
        float[] widths = new float[layout.columns()];
        for (int col = 0; col < widths.length; col++) {
            widths[col] = sheet.getColumnWidthInPixels(col) * 0.75f;
        }
        for (var merge : sheet.getMergedRegions()) {
            if (merge.getFirstRow() == row && merge.getLastRow() == row) {
                int anchor = merge.getFirstColumn();
                for (int col = anchor + 1; col <= merge.getLastColumn(); col++) {
                    widths[anchor] += widths[col];
                }
            }
        }
        return widths;
    }

    private static float requiredHeight(XSSFCell cell, String text, float width, boolean detail) {
        var font = font(cell.getCellStyle().getFont(), text);
        float em = font.getSize2D();
        float available = width - 5 - cell.getCellStyle().getIndention() * em;
        float lineHeight = font.getLineMetrics("国Ag01", FONT_CONTEXT).getHeight();
        var fullWidth = fullWidthFont(font, em);
        float height = 0;
        for (String line : text.split("\\R", -1)) {
            if (line.isEmpty()) {
                height += lineHeight;
                continue;
            }
            boolean cjkDetail = detail && line.codePoints().anyMatch(ExcelWriter::eastAsian);
            var lineFont = cjkDetail ? fullWidth : font;
            // WPS uses a larger text box for CJK glyphs than the Java font's ascent/descent sum.
            float advance = cjkDetail ? Math.max(lineHeight, em * 1.7f) : lineHeight;
            float lineWidth = Math.max(1, cjkDetail ? available - em * 0.5f : available);
            var attributed = new AttributedString(line);
            attributed.addAttribute(TextAttribute.FONT, lineFont);
            var wrap = new LineBreakMeasurer(attributed.getIterator(), BreakIterator.getLineInstance(Locale.CHINA), FONT_CONTEXT);
            while (wrap.getPosition() < line.length()) {
                wrap.nextLayout(lineWidth);
                height += advance;
            }
        }
        return (float) Math.ceil(height + (detail ? 6 : 4));
    }

    private static Font fullWidthFont(Font font, float em) {
        double width = font.getStringBounds("国", FONT_CONTEXT).getWidth();
        return width > 0 && width < em
                ? font.deriveFont(java.awt.geom.AffineTransform.getScaleInstance(em / width, 1)) : font;
    }

    private static boolean eastAsian(int codePoint) {
        return switch (Character.UnicodeScript.of(codePoint)) {
            case HAN, HANGUL, HIRAGANA, KATAKANA, BOPOMOFO -> true;
            default -> false;
        };
    }

    private static Font font(XSSFFont spreadsheetFont, String text) {
        int style = (spreadsheetFont.getBold() ? Font.BOLD : Font.PLAIN)
                | (spreadsheetFont.getItalic() ? Font.ITALIC : Font.PLAIN);
        int points = Math.max(1, spreadsheetFont.getFontHeightInPoints());
        var font = new Font(spreadsheetFont.getFontName(), style, points);
        if (font.canDisplayUpTo(text.replace("\n", "")) < 0) {
            return font;
        }
        for (String candidate : List.of("SimSun", "Songti SC", Font.SERIF)) {
            var alternative = new Font(candidate, style, points);
            if (alternative.canDisplayUpTo(text.replace("\n", "")) < 0) {
                return alternative;
            }
        }
        return font;
    }

    private static CaseException overflow(XSSFSheet sheet, int row, int column) {
        return new CaseException("LAYOUT_OVERFLOW", sheet.getSheetName() + "!" + column(column) + row + " 的内容超过可打印行高");
    }

    private static void writeTotals(XSSFSheet sheet, Layout layout, CellStyles styles, int rowNumber) {
        for (int col : sumColumns(layout)) {
            var cell = sheet.getRow(rowNumber - 1).getCell(col,
                    org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            if (col != layout.quantityTotalColumn()) {
                cell.setCellStyle(styles.forCell(cell, AMOUNT_FORMAT, false));
            }
            cell.setCellFormula(totalFormula(layout, rowNumber, col));
        }
    }

    private static List<Integer> sumColumns(Layout layout) {
        var columns = new java.util.TreeSet<>(layout.moneyColumns().keySet());
        columns.add(layout.totalColumn());
        if (layout.quantityTotalColumn() >= 0) {
            columns.add(layout.quantityTotalColumn());
        }
        return List.copyOf(columns);
    }

    private static String rowFormula(Layout layout, int row) {
        String range = layout.columns() == 11 ? "D" + row + ",F" + row + ":J" + row : "F" + row + ":H" + row;
        return blankOrSum(range);
    }

    private static String totalFormula(Layout layout, int total, int column) {
        String letter = column(column);
        return blankOrSum(letter + layout.firstRow() + ":" + letter + (total - 1));
    }

    private static String blankOrSum(String range) {
        return "IF(COUNT(" + range + ")=0,\"\",SUM(" + range + "))";
    }

    private static String column(int col) {
        return CellReference.convertNumToColString(col);
    }

    private static boolean matches(XSSFCell cell, Object value) {
        if (value == null) {
            return cell == null || cell.getCellType() == CellType.BLANK;
        }
        if (cell == null) {
            return false;
        }
        return switch (value) {
            case BigDecimal amount -> cell.getCellType() == CellType.NUMERIC
                    && amount.compareTo(BigDecimal.valueOf(cell.getNumericCellValue())) == 0;
            case LocalDate date -> cell.getCellType() == CellType.NUMERIC
                    && date.equals(cell.getLocalDateTimeCellValue().toLocalDate());
            case String text -> cell.getCellType() == CellType.STRING && text.equals(cell.getStringCellValue());
            default -> false;
        };
    }

    private static void verifyFormula(XSSFSheet sheet, int row, int col, BigDecimal expected, String formula) throws IOException {
        var cell = sheet.getRow(row - 1).getCell(col);
        var cellName = column(col) + row;
        require(cell != null && cell.getCellType() == CellType.FORMULA && cell.getCellFormula().equals(formula),
                "合计公式不符 " + cellName);
        if (expected == null) {
            require(cell.getCachedFormulaResultType() == CellType.STRING && cell.getStringCellValue().isEmpty(),
                    "合计缓存应为空 " + cellName);
            return;
        }
        require(cell.getCachedFormulaResultType() == CellType.NUMERIC, "合计缓存缺失 " + cellName);
        var value = BigDecimal.valueOf(cell.getNumericCellValue());
        require(value.setScale(Math.max(2, expected.scale()), RoundingMode.HALF_UP).compareTo(expected) == 0,
                "合计缓存金额不符 " + cellName);
    }

    private static void paginate(XSSFWorkbook book, XSSFSheet sheet, Layout layout, int total, int extra) {
        int printLastRow = layout.printLastRow() + extra;
        book.setPrintArea(0, 0, layout.columns() - 1, 0, printLastRow - 1);
        if (sheet.getCTWorksheet().isSetAutoFilter()) {
            sheet.setAutoFilter(new CellRangeAddress(layout.firstRow() - 2, total - 2, 0, layout.columns() - 1));
        }
        for (int rowBreak : sheet.getRowBreaks()) {
            sheet.removeRowBreak(rowBreak);
        }
        sheet.setRepeatingRows(new CellRangeAddress(0, layout.firstRow() - 2, -1, -1));
        var setup = sheet.getPrintSetup();
        setup.setFitWidth((short) 1);
        setup.setFitHeight((short) 0);
        sheet.setAutobreaks(true);
        float contentWidth = 0;
        for (int col = 0; col < layout.columns(); col++) {
            contentWidth += sheet.getColumnWidthInPixels(col) * 0.75f;
        }
        double widthPoints = paper(layout.landscape() ? 297 : 210) - 72
                * (sheet.getMargin(PageMargin.LEFT) + sheet.getMargin(PageMargin.RIGHT));
        double heightPoints = paper(layout.landscape() ? 210 : 297) - 72
                * (sheet.getMargin(PageMargin.TOP) + sheet.getMargin(PageMargin.BOTTOM));
        int scale = Math.min(100, (int) Math.floor(100 * (widthPoints - 4) / contentWidth));
        float remaining = (float) (heightPoints * 100 / scale - 4) - height(sheet, 1, layout.firstRow() - 1);
        float pageBody = remaining;
        float footer = height(sheet, total, printLastRow);
        boolean pageHasDetail = false;
        boolean breaks = false;
        for (int row = layout.firstRow(); row < total; row++) {
            float detail = sheet.getRow(row - 1).getHeightInPoints() + (row == total - 1 ? footer : 0);
            if (detail > pageBody) {
                throw new CaseException("LAYOUT_OVERFLOW", sheet.getSheetName() + " 第 " + row + " 行及签字区超出可打印高度");
            }
            if (detail > remaining && pageHasDetail) {
                sheet.setRowBreak(row - 2);
                remaining = pageBody;
                breaks = true;
            }
            pageHasDetail = true;
            remaining -= detail;
        }
        // Explicit breaks keep the final detail with its signature block on multi-page sheets.
        sheet.setFitToPage(!breaks);
        if (breaks) {
            setup.setScale((short) scale);
        }
    }

    private static float height(XSSFSheet sheet, int first, int last) {
        float result = 0;
        for (int row = first; row <= last; row++) {
            result += sheet.getRow(row - 1).getHeightInPoints();
        }
        return result;
    }

    private static double paper(int mm) {
        return mm * 72 / 25.4;
    }

    private static void require(boolean condition, String issue) throws IOException {
        if (!condition) {
            throw new IOException(issue);
        }
    }

    private record StyleKey(short original, String format, boolean wrap) {}

    private static final class CellStyles {
        private final XSSFWorkbook book;
        private final Map<StyleKey, XSSFCellStyle> variations = new HashMap<>();

        CellStyles(XSSFWorkbook book) {
            this.book = book;
        }

        XSSFCellStyle forCell(XSSFCell cell, String format, boolean wrap) {
            XSSFCellStyle original = cell.getCellStyle();
            return variations.computeIfAbsent(new StyleKey(original.getIndex(), format, wrap), key -> {
                var style = book.createCellStyle();
                style.cloneStyleFrom(original);
                if (key.format() != null) {
                    style.setDataFormat(book.createDataFormat().getFormat(key.format()));
                }
                if (key.wrap()) {
                    style.setWrapText(true);
                    style.setShrinkToFit(false);
                }
                return style;
            });
        }
    }
}
