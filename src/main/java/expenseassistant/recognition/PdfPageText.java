package expenseassistant.recognition;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class PdfPageText extends PDFTextStripper {
    private final PageText.Builder content = new PageText.Builder();
    private final double width;
    private final double height;
    private final PDRectangle crop;
    private final Map<TextPosition, PageResult.Bounds> inkPositions;
    private PageResult.Bounds currentInk;

    private PdfPageText(PDDocument document, int page, boolean useInkBounds) {
        var source = document.getPage(page - 1);
        crop = source.getCropBox();
        boolean swapped = Math.floorMod(source.getRotation(), 180) != 0;
        width = swapped ? crop.getHeight() : crop.getWidth();
        height = swapped ? crop.getWidth() : crop.getHeight();
        inkPositions = useInkBounds ? new IdentityHashMap<>() : null;
        setStartPage(page);
        setEndPage(page);
        setSortByPosition(true);
    }

    static PageText read(PDDocument document, int page) throws IOException {
        return read(document, page, false);
    }

    static PageText read(PDDocument document, int page, boolean useInkBounds) throws IOException {
        var stripper = new PdfPageText(document, page, useInkBounds);
        stripper.getText(document);
        return stripper.content.toPageText(PageResult.Method.PDF_TEXT);
    }

    @Override
    protected void showGlyph(Matrix textMatrix, PDFont font, int code, Vector displacement) throws IOException {
        if (inkPositions != null) currentInk = glyphBounds(textMatrix, font, code, displacement);
        super.showGlyph(textMatrix, font, code, displacement);
    }

    @Override
    protected void processTextPosition(TextPosition position) {
        if (inkPositions != null && currentInk != null) inkPositions.put(position, currentInk);
        super.processTextPosition(position);
    }

    private PageResult.Bounds glyphBounds(Matrix textMatrix, PDFont font, int code, Vector displacement)
            throws IOException {
        if (!(font instanceof PDVectorFont outlineFont) || font.isVertical()
                || getGraphicsState().getTextState().getRenderingMode() != RenderingMode.FILL) {
            return null;
        }
        var outline = outlineFont.getNormalizedPath(code);
        if (outline == null) return null;
        // TextPosition estimates height and ends at the baseline. Use the outline the PDF renderer draws.
        var transform = textMatrix.createAffineTransform();
        transform.concatenate(font.getFontMatrix().createAffineTransform());
        if (!font.isEmbedded() && !font.isStandard14() && font.hasExplicitWidth(code)) {
            float widthFromFont = font.getWidthFromFont(code);
            if (widthFromFont > 0 && displacement.getX() > 0) {
                transform.scale(displacement.getX() * 1000 / widthFromFont, 1);
            }
        }
        var ink = transform.createTransformedShape(outline).getBounds2D();
        if (ink.isEmpty() || ink.getMinX() < crop.getLowerLeftX() || ink.getMaxX() > crop.getUpperRightX()
                || ink.getMinY() < crop.getLowerLeftY() || ink.getMaxY() > crop.getUpperRightY()) {
            return null;
        }
        return PageResult.Bounds.fromEdges(ink.getMinX() - crop.getLowerLeftX(), crop.getUpperRightY() - ink.getMaxY(),
                ink.getMaxX() - crop.getLowerLeftX(), crop.getUpperRightY() - ink.getMinY(), width, height);
    }

    @Override
    protected void writeString(String value, List<TextPosition> positions) {
        int offset = 0;
        for (TextPosition position : positions) {
            String characters = position.getUnicode();
            int start = value.indexOf(characters, offset);
            if (start < 0 || characters.isEmpty()) {
                continue;
            }
            content.append(value.substring(offset, start), null);
            content.append(characters, bounds(position));
            offset = start + characters.length();
        }
        content.append(value.substring(offset), null);
    }

    private PageResult.Bounds bounds(TextPosition position) {
        if (inkPositions != null) return inkPositions.get(position);
        return width > 0 && height > 0
                ? PageResult.Bounds.fromEdges(position.getX(), position.getY() - position.getHeight(),
                position.getX() + position.getWidth(), position.getY(), width, height) : null;
    }

    @Override
    protected void writeLineSeparator() {
        content.append("\n", null);
    }

    @Override
    protected void writeWordSeparator() {
        content.append(" ", null);
    }
}
