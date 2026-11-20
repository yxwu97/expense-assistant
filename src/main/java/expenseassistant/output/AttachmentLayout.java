package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.output.ExportSnapshot.AttachmentPage;
import expenseassistant.output.ExportSnapshot.Layout;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class AttachmentLayout {
    static final Settings SETTINGS = Settings.load();
    private static final FontRenderContext FONT_CONTEXT =
            new FontRenderContext(new AffineTransform(), true, true);

    public record Sheet(List<AttachmentPage> pages, boolean fullPage) {
        public Sheet {
            pages = List.copyOf(pages);
        }
    }

    private record SourcePage(String docId, int page) {}

    private AttachmentLayout() {}

    public static List<Sheet> paginate(List<AttachmentPage> pages) {
        var sheets = new ArrayList<Sheet>();
        var included = new HashSet<SourcePage>();
        AttachmentPage upper = null;
        for (AttachmentPage page : pages) {
            if (!included.add(new SourcePage(page.docId(), page.sourcePage()))) {
                throw new CaseException("SOURCE_CHANGED", "打印清册重复引用了原件 " + location(page));
            }
            if (page.layout() == Layout.FULL_PAGE) {
                if (upper != null) {
                    sheets.add(new Sheet(List.of(upper), false));
                    upper = null;
                }
                sheets.add(new Sheet(List.of(page), true));
            } else if (upper == null) {
                upper = page;
            } else {
                sheets.add(new Sheet(List.of(upper, page), false));
                upper = null;
            }
        }
        if (upper != null) {
            sheets.add(new Sheet(List.of(upper), false));
        }
        return List.copyOf(sheets);
    }

    static List<String> captionLines(AttachmentPage page) {
        String caption = page.caption() == null ? "" : page.caption();
        if (caption.codePointCount(0, caption.length()) > SETTINGS.maxCharacters) {
            throw overflow(page);
        }
        var lines = new ArrayList<String>();
        Font font = captionFont();
        // Use the inner image width for text, leaving at least 2 mm at each side of the table.
        double width = SETTINGS.imageWidthMm * 72 / 25.4;
        for (String paragraph : caption.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
            } else {
                wrap(paragraph, font, width, lines);
            }
            if (lines.size() > SETTINGS.maxLines) {
                throw overflow(page);
            }
        }
        return List.copyOf(lines);
    }

    private static Font captionFont() {
        int points = (int) Math.ceil(SETTINGS.fontPoints);
        for (String name : List.of(SETTINGS.font, "SimSun", "Songti SC")) {
            var font = new Font(name, Font.PLAIN, points);
            if (!font.getFamily().equals(Font.DIALOG)) {
                return font.deriveFont((float) SETTINGS.fontPoints);
            }
        }
        return new Font(Font.SERIF, Font.PLAIN, points).deriveFont((float) SETTINGS.fontPoints);
    }

    private static void wrap(String text, Font font, double maxWidth, List<String> lines) {
        var breaks = BreakIterator.getLineInstance(Locale.SIMPLIFIED_CHINESE);
        breaks.setText(text);
        int start = 0;
        while (start < text.length()) {
            int fit = start;
            int desiredBreak = start;
            while (fit < text.length()) {
                int next = text.offsetByCodePoints(fit, 1);
                String candidate = text.substring(start, next).replace("\t", "    ");
                double width = font.getStringBounds(candidate, FONT_CONTEXT).getWidth();
                if (font.canDisplayUpTo(candidate) >= 0) {
                    width = Math.max(width, candidate.codePointCount(0, candidate.length()) * SETTINGS.fontPoints);
                }
                if (width > maxWidth && fit > start) {
                    break;
                }
                fit = next;
                if (breaks.isBoundary(fit)) {
                    desiredBreak = fit;
                }
            }
            int end = fit == text.length() || desiredBreak == start ? fit : desiredBreak;
            lines.add(text.substring(start, end));
            start = end;
        }
    }

    private static CaseException overflow(AttachmentPage page) {
        return new CaseException("LAYOUT_OVERFLOW", "凭证说明超出两行可打印范围 " + location(page));
    }

    static String location(AttachmentPage page) {
        return page.relativePath() + "，第 " + page.sourcePage() + " 页";
    }

    static int twips(double millimeters) {
        return (int) Math.floor(millimeters * 1440 / 25.4);
    }

    static int emu(double millimeters) {
        return (int) Math.floor(millimeters * 36_000);
    }

    record Settings(double widthMm, double heightMm, double marginMm, double slotMm, double gapMm,
                    double footerReserveMm, double imageWidthMm, double imageHeightMm, float imageDpi,
                    String font, double fontPoints, double linePoints, int maxLines, int maxCharacters) {
        private static Settings load() {
            try (var resource = AttachmentLayout.class.getResourceAsStream("/output/word-layout.properties")) {
                if (resource == null) {
                    throw new IOException("缺少 Word 附件版式参数");
                }
                var properties = new Properties();
                properties.load(resource);
                return new Settings(number(properties, "page.width.mm"), number(properties, "page.height.mm"),
                        number(properties, "page.margin.mm"), number(properties, "slot.height.mm"),
                        number(properties, "slot.gap.mm"), number(properties, "page.footer.reserve.mm"),
                        number(properties, "image.max.width.mm"), number(properties, "image.max.height.mm"),
                        (float) number(properties, "image.dpi"), properties.getProperty("caption.font"),
                        number(properties, "caption.size.pt"), number(properties, "caption.line.spacing.pt"),
                        (int) number(properties, "caption.max.lines"), (int) number(properties, "caption.max.characters"));
            } catch (IOException error) {
                throw new IllegalStateException("无法读取 Word 附件版式参数", error);
            }
        }

        private static double number(Properties properties, String name) {
            return Double.parseDouble(properties.getProperty(name));
        }

        double fullSlotMm() {
            return heightMm - 2 * marginMm - footerReserveMm;
        }

        double fullImageMm() {
            return fullSlotMm() - (slotMm - imageHeightMm);
        }
    }
}
