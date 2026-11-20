package expenseassistant.recognition;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

public final class AttachmentImages {
    public enum CropMode { NONE, TRIP_STATEMENT, INVOICE_PHOTO }

    private static final int REFERENCE_DPI = 300;
    private static final int PDF_MARGIN = (int) Math.ceil(2 * REFERENCE_DPI / 25.4);
    private static final int FOOTER_GAP = (int) Math.ceil(5 * REFERENCE_DPI / 25.4);
    private static final int CORNER_SIZE = 5;
    private static final int COLOR_TOLERANCE = 4;
    private static final int FOOTER_PAD = 2;
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?m)^\\h*(?:第\\h*\\d+\\h*页|(?:页码\\h*[:：]\\h*)?\\d+\\h*/\\h*\\d+)\\h*$");

    private AttachmentImages() {}

    public static BufferedImage render(Path input, int sourcePage, float dpi, CropMode mode) throws IOException {
        if (mode == null || mode == CropMode.NONE) {
            return PageImages.render(input, sourcePage, dpi);
        }
        if (PageImages.isPdf(input)) {
            return mode == CropMode.TRIP_STATEMENT
                    ? renderTripPdf(input, sourcePage, dpi) : PageImages.render(input, sourcePage, dpi);
        }
        var image = whiteBackground(PageImages.render(input, sourcePage, dpi));
        int margin = (int) Math.ceil(Math.min(image.getWidth(), image.getHeight()) * 0.01);
        checkInterrupted();
        return crop(image, candidateBounds(image, margin));
    }

    private static BufferedImage renderTripPdf(Path input, int page, float dpi) throws IOException {
        try (var document = Loader.loadPDF(input.toFile())) {
            var image = PageImages.render(document, page, REFERENCE_DPI);
            var background = BlankPixels.find(image);
            if (background != null) {
                int endRow = footerStart(document, page, background);
                image = crop(image, background.cropBounds(PDF_MARGIN, endRow));
            }
            checkInterrupted();
            return scale(image, dpi / REFERENCE_DPI);
        }
    }

    static Rectangle candidateBounds(BufferedImage image, int margin) throws InterruptedIOException {
        var background = BlankPixels.find(whiteBackground(image));
        return background == null ? fullBounds(image) : background.cropBounds(margin, image.getHeight());
    }

    private static BufferedImage whiteBackground(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) return image;
        var opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = opaque.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, opaque.getWidth(), opaque.getHeight());
            graphics.drawImage(image, 0, 0, null);
            return opaque;
        } finally {
            graphics.dispose();
        }
    }

    private static Rectangle fullBounds(BufferedImage image) {
        return new Rectangle(0, 0, image.getWidth(), image.getHeight());
    }

    private static BufferedImage crop(BufferedImage image, Rectangle bounds) {
        return bounds.equals(fullBounds(image)) ? image
                : image.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    private static BufferedImage scale(BufferedImage image, float scale) {
        if (scale == 1) return image;
        int width = Math.max(1, Math.round(image.getWidth() * scale));
        int height = Math.max(1, Math.round(image.getHeight() * scale));
        var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, width, height, null);
            return scaled;
        } finally {
            graphics.dispose();
            image.flush();
        }
    }

    private static int footerStart(PDDocument document, int page, BlankPixels background) {
        // PDFTextStripper positions and the raster share an origin in an unrotated crop box.
        if (document.getPage(page - 1).getRotation() != 0) return background.height();
        try {
            var text = PdfPageText.read(document, page, true);
            var lines = PAGE_NUMBER.matcher(text.text());
            while (lines.find()) {
                var bounds = text.bounds(lines.start(), lines.end());
                if (bounds == null || bounds.y() < 0.85 || bounds.width() == 0 || bounds.height() == 0
                        || !fullyPositioned(text, lines.start(), lines.end())) {
                    continue;
                }
                var box = footerPixels(bounds, background);
                if (!otherFooterText(text, lines.start(), lines.end(), (double) box.y / background.height())
                        && background.isolatedFooter(box)) return box.y;
            }
        } catch (IOException | RuntimeException unreadableText) {
            // Without usable page coordinates only the outer blank border can be removed.
        }
        return background.height();
    }

    private static boolean otherFooterText(PageText text, int start, int end, double top) {
        return text.spans().stream().anyMatch(span -> (span.end() <= start || span.start() >= end)
                && span.bounds().y() + span.bounds().height() >= top);
    }

    private static boolean fullyPositioned(PageText text, int start, int end) {
        var positioned = new BitSet(end - start);
        for (var span : text.spans()) {
            if (span.end() <= start) continue;
            if (span.start() >= end) break;
            if (span.bounds().width() > 0 && span.bounds().height() > 0) {
                positioned.set(Math.max(start, span.start()) - start, Math.min(end, span.end()) - start);
            }
        }
        for (int index = positioned.nextClearBit(0); index < end - start; index = positioned.nextClearBit(index + 1)) {
            char character = text.text().charAt(start + index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return false;
        }
        return true;
    }

    private static Rectangle footerPixels(PageResult.Bounds bounds, BlankPixels background) {
        int left = Math.max(0, (int) Math.floor(bounds.x() * background.width()) - FOOTER_PAD);
        int top = Math.max(0, (int) Math.floor(bounds.y() * background.height()) - FOOTER_PAD);
        int right = Math.min(background.width(),
                (int) Math.ceil((bounds.x() + bounds.width()) * background.width()) + FOOTER_PAD);
        int bottom = Math.min(background.height(),
                (int) Math.ceil((bounds.y() + bounds.height()) * background.height()) + FOOTER_PAD);
        return new Rectangle(left, top, right - left, bottom - top);
    }

    private static int backgroundColor(BufferedImage image) {
        int right = image.getWidth() - CORNER_SIZE;
        int bottom = image.getHeight() - CORNER_SIZE;
        if (right < CORNER_SIZE || bottom < CORNER_SIZE) return -1;
        int[] colors = { cornerColor(image, 0, 0), cornerColor(image, right, 0),
                cornerColor(image, 0, bottom), cornerColor(image, right, bottom) };
        for (int omitted = 0; omitted < colors.length; omitted++) {
            int[] cluster = new int[3];
            for (int index = 0, included = 0; index < colors.length; index++) {
                if (index != omitted) cluster[included++] = colors[index];
            }
            if (uniform(cluster)) {
                return uniform(colors) ? medianColor(colors) : medianColor(cluster);
            }
        }
        return -1;
    }

    private static int cornerColor(BufferedImage image, int x, int y) {
        int[] sample = image.getRGB(x, y, CORNER_SIZE, CORNER_SIZE, null, 0, CORNER_SIZE);
        for (int index = 0; index < sample.length; index++) sample[index] &= 0xffffff;
        if (!uniform(sample)) return -1;
        int color = medianColor(sample);
        int red = channel(color, 16), green = channel(color, 8), blue = channel(color, 0);
        int darkest = Math.min(red, Math.min(green, blue));
        return darkest >= 235 && Math.max(red, Math.max(green, blue)) - darkest <= 5 ? color : -1;
    }

    private static boolean uniform(int[] colors) {
        for (int color : colors) {
            if (color == -1) return false;
        }
        for (int shift = 0; shift <= 16; shift += 8) {
            int min = 255, max = 0;
            for (int color : colors) {
                int value = channel(color, shift);
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            if (max - min > COLOR_TOLERANCE) return false;
        }
        return true;
    }

    private static int medianColor(int[] colors) {
        int median = 0;
        int[] values = new int[colors.length];
        for (int shift = 0; shift <= 16; shift += 8) {
            for (int index = 0; index < colors.length; index++) values[index] = channel(colors[index], shift);
            Arrays.sort(values);
            median |= ((values[(values.length - 1) / 2] + values[values.length / 2]) / 2) << shift;
        }
        return median;
    }

    private static int channel(int rgb, int shift) {
        return (rgb >>> shift) & 0xff;
    }

    private static boolean matchesBackground(int pixel, int background) {
        return Math.abs(channel(pixel, 0) - channel(background, 0)) <= COLOR_TOLERANCE
                && Math.abs(channel(pixel, 8) - channel(background, 8)) <= COLOR_TOLERANCE
                && Math.abs(channel(pixel, 16) - channel(background, 16)) <= COLOR_TOLERANCE;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消附件渲染");
    }

    private record BlankPixels(int width, int height, BitSet[] connected) {
        static BlankPixels find(BufferedImage image) throws InterruptedIOException {
            int color = backgroundColor(image);
            if (color < 0) return null;
            int width = image.getWidth(), height = image.getHeight();
            var candidates = new BitSet[height];
            int[] pixels = new int[width];
            for (int y = 0; y < height; y++) {
                checkInterrupted();
                image.getRGB(0, y, width, 1, pixels, 0, width);
                var row = new BitSet(width);
                for (int x = 0; x < width; x++) {
                    if (matchesBackground(pixels[x], color)) row.set(x);
                }
                candidates[y] = row;
            }
            return new BlankPixels(width, height, edgeConnected(candidates, width));
        }

        private static BitSet[] edgeConnected(BitSet[] candidates, int width) throws InterruptedIOException {
            int height = candidates.length;
            var remaining = new ArrayDeque<RowSpan>();
            var blank = new BitSet[height];
            Arrays.setAll(blank, row -> new BitSet(width));
            visit(candidates, blank, remaining, 0, 0, width);
            visit(candidates, blank, remaining, height - 1, 0, width);
            for (int y = 1; y < height - 1; y++) {
                visit(candidates, blank, remaining, y, 0, 1);
                visit(candidates, blank, remaining, y, width - 1, width);
            }
            while (!remaining.isEmpty()) {
                checkInterrupted();
                var span = remaining.removeFirst();
                if (span.y() > 0) {
                    visit(candidates, blank, remaining, span.y() - 1, span.start(), span.end());
                }
                if (span.y() < height - 1) {
                    visit(candidates, blank, remaining, span.y() + 1, span.start(), span.end());
                }
            }
            return blank;
        }

        private static void visit(BitSet[] candidates, BitSet[] blank, ArrayDeque<RowSpan> remaining,
                                  int y, int start, int end) {
            // Expand each run in its own row, then look for vertical neighbors in the same columns.
            var row = candidates[y];
            int pixel = row.nextSetBit(start);
            while (pixel >= 0 && pixel < end) {
                int left = row.previousClearBit(pixel) + 1;
                int right = row.nextClearBit(pixel);
                row.clear(left, right);
                blank[y].set(left, right);
                remaining.addLast(new RowSpan(y, left, right));
                pixel = row.nextSetBit(right);
            }
        }

        Rectangle cropBounds(int margin, int endRow) {
            int left = width, top = height, right = 0, bottom = 0;
            for (int y = 0; y < endRow; y++) {
                int first = connected[y].nextClearBit(0);
                if (first >= width) continue;
                left = Math.min(left, first);
                right = Math.max(right, connected[y].previousClearBit(width - 1) + 1);
                top = Math.min(top, y);
                bottom = y + 1;
            }
            if (right == 0) return new Rectangle(0, 0, width, height);
            left = Math.max(0, left - margin);
            top = Math.max(0, top - margin);
            right = Math.min(width, right + margin);
            bottom = Math.min(height, bottom + margin);
            return new Rectangle(left, top, right - left, bottom - top);
        }

        boolean isolatedFooter(Rectangle box) {
            int gapStart = box.y - FOOTER_GAP;
            if (gapStart <= 0) return false;
            for (int y = gapStart; y < box.y; y++) {
                if (connected[y].nextClearBit(0) < width) return false;
            }
            boolean footerVisible = false;
            for (int y = box.y; y < height; y++) {
                int first = connected[y].nextClearBit(0);
                if (first >= width) continue;
                if (y >= box.y + box.height || first < box.x
                        || connected[y].previousClearBit(width - 1) >= box.x + box.width) {
                    return false;
                }
                footerVisible = true;
            }
            return footerVisible;
        }
    }

    private record RowSpan(int y, int start, int end) {}
}
