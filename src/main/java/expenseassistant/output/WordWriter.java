package expenseassistant.output;

import expenseassistant.casework.CaseException;
import expenseassistant.output.ExportSnapshot.AttachmentPage;
import expenseassistant.recognition.AttachmentImages;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.xwpf.usermodel.LineSpacingRule;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.TableRowHeightRule;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static expenseassistant.output.AttachmentLayout.SETTINGS;
import static expenseassistant.output.AttachmentLayout.emu;
import static expenseassistant.output.AttachmentLayout.twips;

public final class WordWriter {
    public void write(ExportSnapshot snapshot, Map<String, Path> cachedByDocId, Path target) throws IOException {
        List<AttachmentLayout.Sheet> sheets = AttachmentLayout.paginate(snapshot.pages());
        if (sheets.isEmpty()) {
            throw new CaseException("PAGE_UNRENDERABLE", "无可打印的附件页");
        }
        var captions = snapshot.pages().stream().collect(java.util.stream.Collectors.toMap(
                WordWriter::sourceKey, AttachmentLayout::captionLines));
        try (var document = new XWPFDocument()) {
            configurePaper(document);
            for (int index = 0; index < sheets.size(); index++) {
                if (index != 0) {
                    var pageBreak = document.createParagraph();
                    compact(pageBreak, 1);
                    pageBreak.setPageBreak(true);
                }
                appendSheet(document, sheets.get(index), cachedByDocId, captions);
            }
            compact(document.createParagraph(), 1);
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                document.write(output);
            }
        }
    }

    private void appendSheet(XWPFDocument document, AttachmentLayout.Sheet sheet, Map<String, Path> cachedByDocId,
                             Map<String, List<String>> captions) {
        int width = twips(SETTINGS.widthMm()) - 2 * twips(SETTINGS.marginMm());
        var table = document.createTable(sheet.fullPage() ? 1 : 3, 1);
        table.setTableAlignment(TableRowAlign.CENTER);
        table.setWidth(width);
        table.removeBorders();
        table.setCellMargins(0, 0, 0, 0);
        table.getCTTbl().getTblPr().addNewTblLayout().setType(STTblLayoutType.FIXED);
        table.getCTTbl().addNewTblGrid().addNewGridCol().setW(BigInteger.valueOf(width));
        for (int rowIndex = 0; rowIndex < table.getNumberOfRows(); rowIndex++) {
            var row = table.getRow(rowIndex);
            row.setHeightRule(TableRowHeightRule.EXACT);
            row.setCantSplitRow(true);
            double height = sheet.fullPage() ? SETTINGS.fullSlotMm()
                    : rowIndex == 1 ? SETTINGS.gapMm() : SETTINGS.slotMm();
            row.setHeight(twips(height));
            var cell = row.getCell(0);
            cell.setWidth(Integer.toString(width));
            cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
            if (rowIndex == 1 || rowIndex / 2 >= sheet.pages().size()) {
                compact(cell.getParagraphArray(0), 1);
                continue;
            }
            AttachmentPage page = sheet.pages().get(rowIndex / 2);
            addPage(cell, page, cachedByDocId.get(page.docId()), captions.get(sourceKey(page)),
                    sheet.fullPage() ? SETTINGS.fullImageMm() : SETTINGS.imageHeightMm());
        }
    }

    private void addPage(XWPFTableCell cell, AttachmentPage page, Path source, List<String> lines, double height) {
        try {
            if (source == null) {
                throw new IOException("缺少凭证副本");
            }
            BufferedImage image = AttachmentImages.render(source, page.sourcePage(), SETTINGS.imageDpi(), page.cropMode());
            try (var encoded = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", encoded)) {
                    throw new IOException("无法保存打印页图");
                }
                double ratio = Math.min(SETTINGS.imageWidthMm() / image.getWidth(), height / image.getHeight());
                var picture = cell.getParagraphArray(0);
                picture.setAlignment(ParagraphAlignment.CENTER);
                picture.setSpacingBefore(0);
                picture.setSpacingAfter(0);
                try (var bytes = new ByteArrayInputStream(encoded.toByteArray())) {
                    var embedded = picture.createRun().addPicture(bytes, PictureType.PNG, "凭证页.png",
                            emu(image.getWidth() * ratio), emu(image.getHeight() * ratio));
                    embedded.getCTPicture().getNvPicPr().getCNvPr().setDescr(sourceKey(page));
                }
            } finally {
                image.flush();
            }
        } catch (Exception error) {
            throw new CaseException("PAGE_UNRENDERABLE", "无法渲染原件 " + AttachmentLayout.location(page), error);
        }
        var caption = cell.addParagraph();
        compact(caption, SETTINGS.linePoints());
        caption.setAlignment(ParagraphAlignment.CENTER);
        var run = caption.createRun();
        for (var range : List.of(XWPFRun.FontCharRange.ascii, XWPFRun.FontCharRange.hAnsi, XWPFRun.FontCharRange.eastAsia)) {
            run.setFontFamily(SETTINGS.font(), range);
        }
        run.setFontSize(SETTINGS.fontPoints());
        for (int index = 0; index < lines.size(); index++) {
            if (index != 0) {
                run.addBreak();
            }
            run.setText(lines.get(index));
        }
    }

    private static void configurePaper(XWPFDocument document) {
        var section = document.getDocument().getBody().addNewSectPr();
        var size = section.addNewPgSz();
        size.setW(BigInteger.valueOf(twips(SETTINGS.widthMm())));
        size.setH(BigInteger.valueOf(twips(SETTINGS.heightMm())));
        size.setOrient(STPageOrientation.PORTRAIT);
        var margins = section.addNewPgMar();
        BigInteger margin = BigInteger.valueOf(twips(SETTINGS.marginMm()));
        margins.setLeft(margin);
        margins.setRight(margin);
        margins.setBottom(margin);
        margins.setTop(margin);
        margins.setHeader(BigInteger.ZERO);
        margins.setFooter(BigInteger.ZERO);
        margins.setGutter(BigInteger.ZERO);
    }

    private static void compact(XWPFParagraph paragraph, double points) {
        paragraph.setSpacingBefore(0);
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBetween(points, LineSpacingRule.EXACT);
    }

    public void verify(ExportSnapshot snapshot, Path target) throws IOException {
        var sheets = AttachmentLayout.paginate(snapshot.pages());
        try (var input = Files.newInputStream(target); var document = new XWPFDocument(input)) {
            var section = document.getDocument().getBody().getSectPr();
            var margin = BigInteger.valueOf(twips(SETTINGS.marginMm()));
            if (section == null || section.getPgSz() == null || section.getPgMar() == null
                    || !BigInteger.valueOf(twips(SETTINGS.widthMm())).equals(section.getPgSz().getW())
                    || !BigInteger.valueOf(twips(SETTINGS.heightMm())).equals(section.getPgSz().getH())
                    || section.getPgSz().getOrient() != STPageOrientation.PORTRAIT
                    || !margin.equals(section.getPgMar().getLeft()) || !margin.equals(section.getPgMar().getRight())
                    || !margin.equals(section.getPgMar().getBottom()) || !margin.equals(section.getPgMar().getTop())) {
                throw new IOException("附件纸型或页边距校验失败");
            }
            if (document.getTables().size() != sheets.size()
                    || document.getParagraphs().stream().filter(XWPFParagraph::isPageBreak).count() != sheets.size() - 1L) {
                throw new IOException("附件纸页或分页符数量校验失败");
            }
            for (int index = 0; index < sheets.size(); index++) {
                var sheet = sheets.get(index);
                var table = document.getTables().get(index);
                if (table.getNumberOfRows() != (sheet.fullPage() ? 1 : 3)) {
                    throw new IOException("附件槽位数量校验失败");
                }
                for (int rowIndex = 0; rowIndex < table.getNumberOfRows(); rowIndex++) {
                    var cell = table.getRow(rowIndex).getCell(0);
                    if (rowIndex == 1 || rowIndex / 2 >= sheet.pages().size()) {
                        if (!cell.getText().isEmpty() || !pictures(cell).isEmpty()) {
                            throw new IOException("空白附件槽位出现内容");
                        }
                        continue;
                    }
                    verifyPage(cell, sheet.pages().get(rowIndex / 2));
                }
            }
        }
    }

    private static void verifyPage(XWPFTableCell cell, AttachmentPage page) throws IOException {
        var pictures = pictures(cell);
        if (pictures.size() != 1 || pictures.getFirst().getPictureData() == null
                || !sourceKey(page).equals(pictures.getFirst().getCTPicture().getNvPicPr().getCNvPr().getDescr())) {
            throw new IOException("附图来源或顺序校验失败 " + AttachmentLayout.location(page));
        }
        var description = cell.getParagraphArray(1);
        if (description == null || !String.join("\n", AttachmentLayout.captionLines(page)).equals(description.getText())
                || description.getRuns().isEmpty()
                || !SETTINGS.font().equals(description.getRuns().getFirst().getFontFamily(XWPFRun.FontCharRange.eastAsia))) {
            throw new IOException("附件说明校验失败 " + AttachmentLayout.location(page));
        }
    }

    private static List<org.apache.poi.xwpf.usermodel.XWPFPicture> pictures(XWPFTableCell cell) {
        return cell.getParagraphs().stream().flatMap(paragraph -> paragraph.getRuns().stream())
                .flatMap(run -> run.getEmbeddedPictures().stream()).toList();
    }

    private static String sourceKey(AttachmentPage page) {
        return page.docId() + "#" + page.sourcePage();
    }
}
