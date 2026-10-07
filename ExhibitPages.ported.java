package com.wp.wdp.chargeback.auto.response.letter;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.wp.wdp.chargeback.auto.response.model.AppendedExhibits;
import com.wp.wdp.chargeback.auto.response.model.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.Exhibit;
import com.wp.wdp.chargeback.auto.response.model.LetterTemplates;
import com.wp.wdp.chargeback.auto.response.model.NamedDocuments;
import com.wp.wdp.chargeback.auto.response.response.DocumentBase64Response;
import com.wp.wdp.chargeback.auto.response.service.DocumentService;

import lombok.RequiredArgsConstructor;

/**
 * The cited pages, attached to the letter (D4, D5): each page scaled into a frame on a page of its own size, with
 * its attachment label above it; then every page of the filed PDF, the letter's included, stamped with the footer.
 */
@RequiredArgsConstructor
@Component
public final class ExhibitPages {
    private static final Logger log = LoggerFactory.getLogger(ExhibitPages.class);
    private static final String FONTS = "filing/fonts/";
    static final float TOP_BAND = 34f;
    static final float BOTTOM_BAND = 30f;
    static final float LABEL_BASELINE = 26f;
    static final float RULE_BELOW_TOP = 31f;
    static final float FOOTER_BASELINE = 20f;
    static final float SIDE = 36f;
    static final float SMALL = 7.5f;
    private static final float[] GREY = {0x5a / 255f, 0x66 / 255f, 0x75 / 255f};
    private static final float[] RULE = {0xc5 / 255f, 0xcc / 255f, 0xd6 / 255f};
    private static final float[] INK = {0x1b / 255f, 0x24 / 255f, 0x30 / 255f};

    private final DocumentService documentService;

    /** The cited pages, appended from the document API; the footer stamped on every page; the title set. */
    public AppendedExhibits append(CaseRow row, byte[] letter, List<Exhibit> exhibits, NamedDocuments named,
                                   LetterTemplates templates, String footer, String title) {
        List<String> problems = new ArrayList<>();
        Map<Exhibit, PDDocument> sources = new LinkedHashMap<>();
        for (Exhibit exhibit : exhibits) {
            byte[] content;
            try {
                content = fetchDocument(row, exhibit.getRef());
            } catch (RuntimeException exception) {
                problems.add("exhibit " + exhibit.getNumber() + ": " + exhibit.getRef() + " could not be fetched ("
                        + exception.getMessage() + ")");
                continue;
            }
            if (content == null || content.length == 0) {
                problems.add("exhibit " + exhibit.getNumber() + ": no document at " + exhibit.getRef());
                continue;
            }
            try {
                sources.put(exhibit, PDDocument.load(content));
            } catch (IOException exception) {
                problems.add("exhibit " + exhibit.getNumber() + ": " + exhibit.getRef() + " could not be opened ("
                        + exception.getMessage() + ")");
            }
        }
        return assemble(letter, sources, problems, named, templates, footer, title);
    }

    private static AppendedExhibits assemble(byte[] letter, Map<Exhibit, PDDocument> sources, List<String> problems,
                                             NamedDocuments named, LetterTemplates templates, String footer,
                                             String title) {
        int appended = 0;
        try (PDDocument out = PDDocument.load(letter)) {
            PDFont sans = font(out, "DejaVuSans.ttf");
            PDFont bold = font(out, "DejaVuSans-Bold.ttf");
            LayerUtility layers = new LayerUtility(out);
            for (Map.Entry<Exhibit, PDDocument> entry : sources.entrySet()) {
                Exhibit exhibit = entry.getKey();
                PDDocument source = entry.getValue();
                for (int page : exhibit.getPages()) {
                    if (page < 1 || page > source.getNumberOfPages()) {
                        problems.add("exhibit " + exhibit.getNumber() + ": " + exhibit.getRef()
                                + " has no page " + page);
                        continue;
                    }
                    PDPage framed = frame(out, layers, source, page - 1);
                    label(out, framed, sans, bold, named, templates, exhibit, page, problems);
                    appended++;
                }
            }
            if (footer != null) {
                stamp(out, sans, footer, problems);
            }
            if (title != null) {
                out.getDocumentInformation().setTitle(title);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            log.info("attached {} page(s) from the document store; the filing is {} page(s)", appended,
                    out.getNumberOfPages());
            return new AppendedExhibits(bytes.toByteArray(), appended, out.getNumberOfPages(),
                    List.copyOf(problems));
        } catch (IOException exception) {
            throw new IllegalStateException("the attachment pages could not be appended: "
                    + exception.getMessage(), exception);
        } finally {
            // A source page is imported by reference, so a source closes only after the filing is saved
            for (PDDocument source : sources.values()) {
                try {
                    source.close();
                } catch (IOException exception) {
                    log.warn("an attachment source did not close: {}", exception.getMessage());
                }
            }
        }
    }

    /** The source page as a form, scaled uniformly into the frame between the two bands and centred; nothing of it is covered. */
    private static PDPage frame(PDDocument out, LayerUtility layers, PDDocument source, int index) throws IOException {
        PDPage sourcePage = source.getPage(index);
        PDFormXObject form = layers.importPageAsForm(source, index);
        PDRectangle size = sourcePage.getCropBox();
        boolean turned = sourcePage.getRotation() % 180 != 0;
        float width = turned ? size.getHeight() : size.getWidth();
        float height = turned ? size.getWidth() : size.getHeight();
        PDPage target = new PDPage(new PDRectangle(width, height));
        out.addPage(target);

        Rectangle2D shown = form.getMatrix().createAffineTransform()
                .createTransformedShape(form.getBBox().toGeneralPath()).getBounds2D();
        float scale = (float) Math.min(width / shown.getWidth(), (height - TOP_BAND - BOTTOM_BAND) / shown.getHeight());
        float dx = (float) ((width - shown.getWidth() * scale) / 2 - shown.getX() * scale);
        float dy = (float) (BOTTOM_BAND + (height - TOP_BAND - BOTTOM_BAND - shown.getHeight() * scale) / 2
                - shown.getY() * scale);
        try (PDPageContentStream stream = new PDPageContentStream(out, target)) {
            stream.saveGraphicsState();
            stream.transform(new Matrix(scale, 0, 0, scale, dx, dy));
            stream.drawForm(form);
            stream.restoreGraphicsState();
        }
        return target;
    }

    private static void label(PDDocument out, PDPage page, PDFont sans, PDFont bold, NamedDocuments named,
                              LetterTemplates templates, Exhibit exhibit, int original, List<String> problems) {
        String letter = named == null ? null : named.letter(exhibit.getRef());
        if (letter == null || templates == null) {
            return;
        }
        String text = templates.getAttachmentPageLabel().replace("{letter}", letter)
                .replace("{description}", named.description(exhibit.getRef())).replace("{page}", String.valueOf(original));
        String file = templates.getAttachmentPageFile().replace("{fileName}", exhibit.getDisplayName());
        PDRectangle box = page.getMediaBox();
        float top = box.getUpperRightY();
        try (PDPageContentStream stream = new PDPageContentStream(out, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            stream.beginText();
            stream.setFont(bold, SMALL);
            stream.setNonStrokingColor(INK[0], INK[1], INK[2]);
            stream.newLineAtOffset(box.getLowerLeftX() + SIDE, top - LABEL_BASELINE);
            stream.showText(text);
            stream.setFont(sans, SMALL);
            stream.setNonStrokingColor(GREY[0], GREY[1], GREY[2]);
            stream.showText(file);
            stream.endText();
            stream.setStrokingColor(RULE[0], RULE[1], RULE[2]);
            stream.setLineWidth(0.6f);
            stream.moveTo(box.getLowerLeftX() + SIDE, top - RULE_BELOW_TOP);
            stream.lineTo(box.getUpperRightX() - SIDE, top - RULE_BELOW_TOP);
            stream.stroke();
        } catch (IOException | IllegalArgumentException exception) {
            // A label that will not write is a problem line, never a lost filing
            problems.add("attachment " + letter + " p" + original + ": the page label could not be written ("
                    + exception.getMessage() + ")");
        }
    }

    /** D5: "page x of y" on every page, y the whole PDF. */
    private static void stamp(PDDocument out, PDFont sans, String footer, List<String> problems) {
        int total = out.getNumberOfPages();
        for (int index = 0; index < total; index++) {
            PDPage page = out.getPage(index);
            String text = footer.replace("{x}", String.valueOf(index + 1)).replace("{y}", String.valueOf(total));
            PDRectangle box = page.getMediaBox();
            try (PDPageContentStream stream = new PDPageContentStream(out, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                float width = sans.getStringWidth(text) / 1000f * SMALL;
                stream.beginText();
                stream.setFont(sans, SMALL);
                stream.setNonStrokingColor(GREY[0], GREY[1], GREY[2]);
                stream.newLineAtOffset(box.getLowerLeftX() + (box.getWidth() - width) / 2,
                        box.getLowerLeftY() + FOOTER_BASELINE);
                stream.showText(text);
                stream.endText();
            } catch (IOException | IllegalArgumentException exception) {
                problems.add("page " + (index + 1) + ": the footer could not be written (" + exception.getMessage() + ")");
            }
        }
    }

    private static PDFont font(PDDocument out, String file) throws IOException {
        try (InputStream in = new ClassPathResource(FONTS + file).getInputStream()) {
            return PDType0Font.load(out, in);
        }
    }

    public static String textOf(byte[] pdf) {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException exception) {
            throw new IllegalStateException("the letter could not be read back: " + exception.getMessage(), exception);
        }
    }

    public static String textOf(byte[] pdf, int pages) {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(pages, document.getNumberOfPages()));
            return stripper.getText(document);
        } catch (IOException exception) {
            throw new IllegalStateException("the letter could not be read back: " + exception.getMessage(), exception);
        }
    }

    public static int pageCount(byte[] pdf) {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException exception) {
            throw new IllegalStateException("the letter could not be read back: " + exception.getMessage(), exception);
        }
    }

    private byte[] fetchDocument(CaseRow row, String reference) {
        String encoded = documentApiBase64(row, reference);
        if (encoded == null || encoded.isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("the document store returned a body that is not BASE64: "
                    + exception.getMessage(), exception);
        }
    }

    private String documentApiBase64(CaseRow row, String reference) {
        try {
            List<DocumentBase64Response> responses = documentService.getDocumentBase64(
                row.getAcquiringPlatform(),
                row.getCaseNumber(),
                List.of(reference)
            );
            log.info("Document Base64 Response: {}", responses);

            if (responses != null && !responses.isEmpty()) {
                DocumentBase64Response response = responses.get(0);
                if (response != null && response.getBase64Data() != null) {
                    return response.getBase64Data();
                }
            }
            log.warn("No base64 data returned for reference: {}", reference);
            return null;
        } catch (Exception exception) {
            log.error("Failed to fetch document from API for reference {}: {}", reference, exception.getMessage(), exception);
            return null;
        }
    }
}
