package expenseassistant.recognition;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static expenseassistant.recognition.AttachmentImages.CropMode.INVOICE_PHOTO;
import static expenseassistant.recognition.AttachmentImages.CropMode.NONE;
import static expenseassistant.recognition.AttachmentImages.CropMode.TRIP_STATEMENT;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentImagesTest {
    @TempDir Path temporary;

    @Test
    void cropsTheKnownPhotoBoundsWithTwelvePixelsOfPadding() throws Exception {
        var image = whiteImage(1200, 1600);
        fill(image, Color.BLACK, 200, 300, 800, 900);
        var bounds = new Rectangle(188, 288, 824, 924);
        assertEquals(bounds, AttachmentImages.candidateBounds(image, 12));
        var source = temporary.resolve("invoice.png");
        ImageIO.write(image, "png", source.toFile());
        var cropped = AttachmentImages.render(source, 1, 105, INVOICE_PHOTO);
        assertEquals(bounds.width, cropped.getWidth());
        assertEquals(bounds.height, cropped.getHeight());
        assertEquals(Color.WHITE.getRGB(), cropped.getRGB(0, 0));
        assertEquals(Color.BLACK.getRGB(), cropped.getRGB(12, 12));
        assertEquals(1200, AttachmentImages.render(source, 1, 105, NONE).getWidth());
    }

    @Test
    void whiteCompositingRetainsPaleDetachedMarksAndContentAtTheEdge() throws IOException {
        var image = new BufferedImage(100, 120, BufferedImage.TYPE_INT_ARGB);
        fill(image, Color.BLACK, 20, 30, 60, 60);
        image.setRGB(95, 20, new Color(246, 246, 246).getRGB());
        image.setRGB(0, 100, Color.BLACK.getRGB());
        var bounds = new Rectangle(0, 19, 97, 83);
        assertEquals(bounds, AttachmentImages.candidateBounds(image, 1));
        var source = temporary.resolve("transparent.png");
        ImageIO.write(image, "png", source.toFile());
        var crop = AttachmentImages.render(source, 1, 105, INVOICE_PHOTO);
        assertEquals(Color.WHITE.getRGB(), crop.getRGB(0, 0));
        assertEquals(new Color(246, 246, 246).getRGB(), crop.getRGB(95, 1));
        assertEquals(Color.BLACK.getRGB(), crop.getRGB(0, 81));
    }

    @Test
    void cropsTiltedContentAndFallsBackForComplexOrEmptyBackground() throws IOException {
        var image = whiteImage(160, 180);
        var graphics = image.createGraphics();
        try {
            graphics.rotate(Math.toRadians(12), 80, 90);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(40, 55, 80, 70);
        } finally {
            graphics.dispose();
        }
        var bounds = AttachmentImages.candidateBounds(image, 2);
        assertTrue(bounds.width < 160 && bounds.height < 180);
        for (int y = 0; y < 180; y++) {
            for (int x = 0; x < 160; x++) {
                if (image.getRGB(x, y) != Color.WHITE.getRGB()) assertTrue(bounds.contains(x, y));
            }
        }
        fill(image, Color.GRAY, 0, 0, 5, 5);
        fill(image, Color.GRAY, 155, 0, 5, 5);
        assertEquals(new Rectangle(0, 0, 160, 180), AttachmentImages.candidateBounds(image, 2));
        assertEquals(new Rectangle(0, 0, 160, 180), AttachmentImages.candidateBounds(whiteImage(160, 180), 2));
    }

    @Test
    void photoIsCroppedAfterReadingItsJpegExifOrientation() throws IOException {
        var image = whiteImage(80, 120);
        fill(image, Color.BLACK, 16, 24, 32, 64);
        Path source = temporary.resolve("rotated.jpg");
        ImageIO.write(image, "jpeg", source.toFile());
        byte[] exif = {
                (byte) 0xff, (byte) 0xe1, 0, 34, 'E', 'x', 'i', 'f', 0, 0, 'I', 'I',
                42, 0, 8, 0, 0, 0, 1, 0, 0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] jpeg = Files.readAllBytes(source);
        try (var output = Files.newOutputStream(source)) {
            output.write(jpeg, 0, 2);
            output.write(exif);
            output.write(jpeg, 2, jpeg.length - 2);
        }
        var original = AttachmentImages.render(source, 1, 105, NONE);
        assertEquals(120, original.getWidth());
        assertEquals(80, original.getHeight());
        assertEquals(Color.BLACK.getRGB(), original.getRGB(63, 32));
        var cropped = AttachmentImages.render(source, 1, 105, INVOICE_PHOTO);
        assertEquals(66, cropped.getWidth());
        assertEquals(34, cropped.getHeight());
    }

    @Test
    void removesBothIsolatedFooterStylesAndUsesTheSameCropAtBothDpis() throws IOException {
        var source = temporary.resolve("isolated.pdf");
        try (var document = new PDDocument()) {
            addTripPage(document, "X 1 Y", false, false, 0);
            addTripPage(document, "2 / 2", false, false, 0);
            addTripPage(document, "WQZ1/1", false, false, 0);
            addTripPage(document, null, false, false, 0);
            assertEquals("第 1 页", PdfPageText.read(document, 1).text().strip());
            assertEquals("页码：1/1", PdfPageText.read(document, 3).text().strip());
            document.save(source.toFile());
        }
        var body = AttachmentImages.render(source, 4, 300, TRIP_STATEMENT);
        for (int page : new int[]{1, 2, 3}) {
            assertPixels(body, AttachmentImages.render(source, page, 300, TRIP_STATEMENT));
            var preview = AttachmentImages.render(source, page, 105, TRIP_STATEMENT);
            assertEquals(Math.round(body.getWidth() * 105 / 300f), preview.getWidth());
            assertEquals(Math.round(body.getHeight() * 105 / 300f), preview.getHeight());
        }
    }

    @Test
    void retainsFooterWithNearbyInkRotationOrAContentBandTooSmall() throws IOException {
        var source = temporary.resolve("footer-kept.pdf");
        try (var document = new PDDocument()) {
            addTripPage(document, "2 / 2", true, false, 0);
            addTripPage(document, "2 / 2", false, true, 0);
            addTripPage(document, "2 / 2", false, false, 90);
            document.save(source.toFile());
        }
        for (int page : new int[]{1, 2, 3}) {
            assertKeepsAllForeground(source, page);
        }
    }

    @Test
    void retainsTheBottomContentInAnImageOnlyPdfAndLeavesOrdinaryPdfWhole() throws IOException {
        var image = whiteImage(300, 400);
        fill(image, Color.BLACK, 70, 50, 180, 60);
        fill(image, Color.DARK_GRAY, 125, 396, 50, 4);
        var source = temporary.resolve("image-only.pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(300, 400));
            document.addPage(page);
            try (var canvas = new PDPageContentStream(document, page)) {
                canvas.drawImage(LosslessFactory.createFromImage(document, image), 0, 0);
            }
            document.save(source.toFile());
        }
        var original = PageImages.render(source, 1, 300);
        assertKeepsAllForeground(source, 1);
        var cropped = AttachmentImages.render(source, 1, 300, TRIP_STATEMENT);
        assertEquals(Color.DARK_GRAY.getRGB(), cropped.getRGB(cropped.getWidth() / 2, cropped.getHeight() - 1));
        assertPixels(original, AttachmentImages.render(source, 1, 300, NONE));
        assertThrows(IOException.class, () -> AttachmentImages.render(source, 2, 105, TRIP_STATEMENT));
    }

    private static void assertKeepsAllForeground(Path path, int page) throws IOException {
        var original = PageImages.render(path, page, 300);
        var bounds = AttachmentImages.candidateBounds(original, 24);
        assertTrue(bounds.width < original.getWidth() || bounds.height < original.getHeight());
        assertPixels(original.getSubimage(bounds.x, bounds.y, bounds.width, bounds.height),
                AttachmentImages.render(path, page, 300, TRIP_STATEMENT));
    }

    private static void addTripPage(PDDocument document, String footer, boolean nearbyInk, boolean shortGap,
                                    int rotation) throws IOException {
        var page = new PDPage(new PDRectangle(300, 400));
        page.setRotation(rotation);
        document.addPage(page);
        try (var canvas = new PDPageContentStream(document, page)) {
            canvas.addRect(60, 260, 180, 60);
            if (nearbyInk) canvas.addRect(220, 23, 12, 1);
            if (shortGap) canvas.addRect(70, 30, 145, 2);
            canvas.fill();
            if (footer != null) {
                canvas.beginText();
                canvas.setFont(footerFont(document), 10);
                canvas.newLineAtOffset(125, 22);
                canvas.showText(footer);
                canvas.endText();
            }
        }
    }

    private static PDType1Font footerFont(PDDocument document) throws IOException {
        var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        // Supply the Chinese text layer without requiring a platform-specific CJK font in the fixture.
        var unicodeMap = new PDStream(document);
        try (var output = unicodeMap.createOutputStream()) {
            output.write("""
                    /CIDInit /ProcSet findresource begin
                    12 dict begin begincmap
                    /CMapName /FooterText def /CMapType 2 def
                    1 begincodespacerange
                    <00> <FF>
                    endcodespacerange
                    5 beginbfchar
                    <51> <7801>
                    <57> <9875>
                    <58> <7B2C>
                    <59> <9875>
                    <5A> <FF1A>
                    endbfchar
                    endcmap CMapName currentdict /CMap defineresource pop
                    end end
                    """.getBytes(StandardCharsets.US_ASCII));
        }
        font.getCOSObject().setItem(COSName.TO_UNICODE, unicodeMap);
        return font;
    }

    private static void assertPixels(BufferedImage expected, BufferedImage actual) {
        int width = expected.getWidth(), height = expected.getHeight();
        assertEquals(width, actual.getWidth());
        assertEquals(height, actual.getHeight());
        assertArrayEquals(expected.getRGB(0, 0, width, height, null, 0, width),
                actual.getRGB(0, 0, width, height, null, 0, width));
    }

    private static BufferedImage whiteImage(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        fill(image, Color.WHITE, 0, 0, width, height);
        return image;
    }

    private static void fill(BufferedImage image, Color color, int x, int y, int width, int height) {
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(x, y, width, height);
        } finally {
            graphics.dispose();
        }
    }
}
