import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

public class GenerateIcons {
    private static final List<Integer> ICO_SIZES = List.of(16, 20, 24, 32, 40, 48, 64, 128, 256);
    private static final int ICO_HEADER_SIZE = 6;
    private static final int ICO_ENTRY_SIZE = 16;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: GenerateIcons <source.ico> <output-directory>");
        }
        Map<Integer, BufferedImage> originals = readIco(Path.of(args[0]));
        Path output = Files.createDirectories(Path.of(args[1]));
        Map<Integer, byte[]> decorated = new LinkedHashMap<>();
        for (int size : ICO_SIZES) {
            decorated.put(size, encodePng(decorate(originals.get(size), size)));
        }
        Files.write(output.resolve("expense-assistant.ico"), encodeIco(decorated));
        Files.write(output.resolve("expense-assistant.png"), decorated.get(256));
        writeIconset(output, originals.get(256), decorated);
    }

    private static Map<Integer, BufferedImage> readIco(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        ByteBuffer directory = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int directoryEnd = ICO_HEADER_SIZE + ICO_SIZES.size() * ICO_ENTRY_SIZE;
        if (bytes.length < directoryEnd || directory.getShort() != 0
                || directory.getShort() != 1 || directory.getShort() != ICO_SIZES.size()) {
            throw new IOException("Expected an ICO with " + ICO_SIZES.size() + " PNG entries: " + source);
        }
        Map<Integer, BufferedImage> images = new LinkedHashMap<>();
        for (int entry = 0; entry < ICO_SIZES.size(); entry++) {
            int width = iconDimension(directory.get());
            int height = iconDimension(directory.get());
            directory.position(directory.position() + 6);
            int length = directory.getInt();
            int offset = directory.getInt();
            if (width != height || !ICO_SIZES.contains(width) || images.containsKey(width)
                    || length < PNG_SIGNATURE.length || offset < directoryEnd || offset > bytes.length - length
                    || !Arrays.equals(bytes, offset, offset + PNG_SIGNATURE.length,
                            PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
                throw new IOException("Invalid ICO PNG entry " + (entry + 1) + ": " + source);
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes, offset, length));
            if (image == null || image.getWidth() != width || image.getHeight() != height
                    || !image.getColorModel().hasAlpha()) {
                throw new IOException("Invalid ICO image dimensions or alpha in entry " + (entry + 1) + ": " + source);
            }
            images.put(width, image);
        }
        return images;
    }

    private static int iconDimension(byte value) {
        return value == 0 ? 256 : Byte.toUnsignedInt(value);
    }

    private static BufferedImage decorate(BufferedImage source, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, size, size, null);

            graphics.setComposite(AlphaComposite.SrcAtop);
            AffineTransform unrotated = graphics.getTransform();
            graphics.scale(size, size);
            graphics.translate(0.755, 0.755);
            graphics.rotate(-Math.PI / 4);
            drawRibbon(graphics, size);
            if (size == 16) {
                graphics.setTransform(unrotated);
                drawSmallLetters(graphics);
            } else {
                drawLetters(graphics, size);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void drawRibbon(Graphics2D graphics, int size) {
        double halfHeight = size == 16 ? 0.144 : (size <= 24 ? 0.122 : 0.106);
        Path2D ribbon = new Path2D.Double();
        ribbon.moveTo(-1, -halfHeight);
        ribbon.lineTo(1, -halfHeight);
        ribbon.lineTo(1, halfHeight);
        ribbon.lineTo(-1, halfHeight);
        ribbon.closePath();
        graphics.setColor(Color.BLACK);
        graphics.fill(ribbon);
    }

    private static void drawLetters(Graphics2D graphics, int size) {
        double halfHeight = size <= 24 ? 0.080 : 0.070;
        Path2D letters = new Path2D.Double();
        letters.moveTo(-0.052, -halfHeight);
        letters.lineTo(-0.220, -halfHeight);
        letters.lineTo(-0.220, halfHeight);
        letters.lineTo(-0.052, halfHeight);
        letters.moveTo(-0.220, 0);
        letters.lineTo(-0.079, 0);

        letters.moveTo(0.025, halfHeight);
        letters.lineTo(0.132, -halfHeight);
        letters.lineTo(0.239, halfHeight);
        letters.moveTo(0.066, halfHeight * 0.23);
        letters.lineTo(0.198, halfHeight * 0.23);

        graphics.setColor(Color.WHITE);
        float stroke = size <= 24 ? 0.048f : 0.036f;
        graphics.setStroke(new BasicStroke(stroke, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_BEVEL));
        graphics.draw(letters);
    }

    private static void drawSmallLetters(Graphics2D graphics) {
        graphics.setColor(Color.WHITE);
        graphics.fillRect(8, 12, 3, 1);
        graphics.fillRect(8, 13, 2, 1);
        graphics.fillRect(8, 14, 1, 1);
        graphics.fillRect(8, 15, 3, 1);
        graphics.fillRect(13, 9, 1, 1);
        graphics.fillRect(12, 10, 1, 3);
        graphics.fillRect(14, 10, 1, 3);
        graphics.fillRect(13, 11, 1, 1);
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return output.toByteArray();
    }

    private static byte[] encodeIco(Map<Integer, byte[]> frames) {
        int offset = ICO_HEADER_SIZE + frames.size() * ICO_ENTRY_SIZE;
        int size = offset + frames.values().stream().mapToInt(frame -> frame.length).sum();
        ByteBuffer ico = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) frames.size());
        for (var entry : frames.entrySet()) {
            byte dimension = entry.getKey().byteValue();
            int length = entry.getValue().length;
            ico.put(dimension).put(dimension).put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32).putInt(length).putInt(offset);
            offset += length;
        }
        frames.values().forEach(ico::put);
        return ico.array();
    }

    private static void writeIconset(Path output, BufferedImage largest, Map<Integer, byte[]> decorated)
            throws IOException {
        Path iconset = Files.createDirectories(output.resolve("ExpenseAssistant.iconset"));
        for (int size : List.of(16, 32, 128, 256, 512, 1024)) {
            if (!decorated.containsKey(size)) {
                decorated.put(size, encodePng(decorate(largest, size)));
            }
        }
        for (int size : List.of(16, 32, 128, 256, 512)) {
            String name = "icon_" + size + "x" + size;
            Files.write(iconset.resolve(name + ".png"), decorated.get(size));
            Files.write(iconset.resolve(name + "@2x.png"), decorated.get(size * 2));
        }
    }
}
