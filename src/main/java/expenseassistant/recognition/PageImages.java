package expenseassistant.recognition;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

public final class PageImages {
    private PageImages() {}

    public static boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    public static BufferedImage render(Path input, int page, float dpi) throws IOException {
        if (!isPdf(input)) {
            if (page != 1) {
                throw new IOException("图片原件只有一页");
            }
            return image(input);
        }
        try (var document = Loader.loadPDF(input.toFile())) {
            return render(document, page, dpi);
        }
    }

    static BufferedImage render(PDDocument document, int page, float dpi) throws IOException {
        if (page < 1 || page > document.getNumberOfPages()) {
            throw new IOException("凭证页码已失效");
        }
        return new PDFRenderer(document).renderImageWithDPI(page - 1, dpi, ImageType.RGB);
    }

    static BufferedImage image(Path path) throws IOException {
        return decode(path).pixels();
    }

    static ImagePage decode(Path path) throws IOException {
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                throw new IOException("无法打开图片原件");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("无法解码图片原件");
            }
            var reader = readers.next();
            try {
                input.seek(0);
                int orientation = jpegOrientation(input);
                input.seek(0);
                reader.setInput(input, false, true);
                return new ImagePage(orient(reader.read(0), orientation), orientation);
            } finally {
                reader.dispose();
            }
        }
    }

    record ImagePage(BufferedImage pixels, int orientation) {}

    private static int jpegOrientation(ImageInputStream input) throws IOException {
        try {
            if (input.readUnsignedShort() != 0xffd8) {
                return 1;
            }
            while (input.readUnsignedByte() == 0xff) {
                int marker;
                do {
                    marker = input.readUnsignedByte();
                } while (marker == 0xff);
                if (marker == 0xda || marker == 0xd9 || marker == 0) {
                    return 1;
                }
                if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) {
                    continue;
                }
                int payload = input.readUnsignedShort() - 2;
                if (payload < 0) {
                    return 1;
                }
                if (marker == 0xe1) {
                    byte[] metadata = new byte[payload];
                    input.readFully(metadata);
                    int orientation = exifOrientation(metadata);
                    if (orientation >= 2 && orientation <= 8) {
                        return orientation;
                    }
                } else {
                    input.seek(input.getStreamPosition() + payload);
                }
            }
        } catch (EOFException incompleteMetadata) {
            // Let the image reader decide whether pixel data can still be decoded.
        }
        return 1;
    }

    private static int exifOrientation(byte[] bytes) {
        if (bytes.length < 16 || bytes[0] != 'E' || bytes[1] != 'x' || bytes[2] != 'i' || bytes[3] != 'f') {
            return 1;
        }
        var buffer = ByteBuffer.wrap(bytes, 6, bytes.length - 6).slice();
        var byteOrder = buffer.getShort(0);
        if (byteOrder == 0x4949) {
            buffer.order(ByteOrder.LITTLE_ENDIAN);
        } else if (byteOrder != 0x4d4d) {
            return 1;
        }
        int offset = buffer.getInt(4);
        if (buffer.getShort(2) != 42 || offset < 8 || offset > buffer.limit() - 2) {
            return 1;
        }
        int entries = Math.min(Short.toUnsignedInt(buffer.getShort(offset)), (buffer.limit() - offset - 2) / 12);
        for (int i = 0; i < entries; i++) {
            int entry = offset + 2 + 12 * i;
            if (buffer.getShort(entry) == 0x112 && buffer.getShort(entry + 2) == 3 && buffer.getInt(entry + 4) == 1) {
                return Short.toUnsignedInt(buffer.getShort(entry + 8));
            }
        }
        return 1;
    }

    static BufferedImage orient(BufferedImage image, int orientation) {
        int width = image.getWidth();
        int height = image.getHeight();
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> null;
        };
        if (transform == null) {
            return image;
        }
        boolean swapped = orientation >= 5;
        var oriented = new BufferedImage(swapped ? height : width, swapped ? width : height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = oriented.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, oriented.getWidth(), oriented.getHeight());
            graphics.drawImage(image, transform, null);
            return oriented;
        } finally {
            graphics.dispose();
        }
    }
}
