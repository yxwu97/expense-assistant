package expenseassistant.support;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class ReceiptFixtures {
    private ReceiptFixtures() {}

    public static void receipt(Path file, String issuer, String receiptNumber, String total) throws IOException {
        writePdf(file, "Receipt\nMerchant: " + issuer + "\nReceipt No: " + receiptNumber
                + "\nDate: 2026-11-20\nTotal: " + total);
    }

    public static void writePdf(Path file, String text) throws IOException {
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var canvas = new PDPageContentStream(document, page)) {
                canvas.beginText();
                canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                canvas.setLeading(16);
                canvas.newLineAtOffset(72, 720);
                for (String line : text.split("\\R")) {
                    canvas.showText(line);
                    canvas.newLine();
                }
                canvas.endText();
            }
            document.save(file.toFile());
        }
    }
}
