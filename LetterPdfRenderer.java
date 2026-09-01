package com.wp.gcp.autocontest.letter;

import java.io.ByteArrayOutputStream;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Renders the {@link RepresentmentLetter} to PDF - the artifact the networks accept.
 *
 * <p>Rendered from the letter's STRUCTURED sections, deliberately not from its HTML twin:
 * parsing one rendering to produce another would put a second interpretation between the audit
 * object and the filed artifact. Same data, two renderers, no chain.
 */
public final class LetterPdfRenderer {

    private static final Font H1 = FontFactory.getFont(FontFactory.TIMES_BOLD, 16);
    private static final Font H3 = FontFactory.getFont(FontFactory.TIMES_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.TIMES, 11);
    private static final Font BOLD = FontFactory.getFont(FontFactory.TIMES_BOLD, 11);
    private static final Font SMALL = FontFactory.getFont(FontFactory.TIMES, 8);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 9);

    private LetterPdfRenderer() {
    }

    public static byte[] render(RepresentmentLetter letter) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 56, 56, 48, 48);
            PdfWriter.getInstance(doc, out);
            doc.open();

            PdfPTable header = new PdfPTable(4);
            header.setWidthPercentage(100);
            headerCell(header, "Merchant", letter.merchantId());
            headerCell(header, "ARN", letter.arn());
            headerCell(header, "Chargeback Reason", letter.chargebackReason());
            headerCell(header, "Payment Details", letter.cardSummary());
            doc.add(header);

            Paragraph date = new Paragraph(nvl(letter.letterDate()), BODY);
            date.setAlignment(Element.ALIGN_RIGHT);
            date.setSpacingBefore(12);
            doc.add(date);

            Paragraph title = new Paragraph("Chargeback Representment Letter", H1);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingBefore(12);
            title.setSpacingAfter(12);
            doc.add(title);

            doc.add(spaced(new Paragraph(nvl(letter.opening()), BOLD)));
            doc.add(spaced(new Paragraph(nvl(letter.caseFacts()), BODY)));
            if (letter.merchantDescription() != null && !letter.merchantDescription().isBlank()) {
                doc.add(spaced(new Paragraph(letter.merchantDescription(), BODY)));
            }

            for (RepresentmentLetter.ArgumentPoint p : letter.argumentPoints()) {
                Paragraph t = new Paragraph(p.title(), H3);
                t.setSpacingBefore(10);
                doc.add(t);
                doc.add(spaced(new Paragraph(nvl(p.body()), BODY)));
                if (!p.decidedOn().isEmpty()) {
                    doc.add(new Paragraph("Established on: "
                            + String.join(", ", p.decidedOn()), SMALL));
                }
            }

            if (!letter.evidenceExhibit().isEmpty()) {
                Paragraph et = new Paragraph("Authorization — Transaction Details", H3);
                et.setSpacingBefore(14);
                doc.add(et);
                PdfPTable exhibit = new PdfPTable(2);
                exhibit.setWidthPercentage(70);
                exhibit.setSpacingBefore(6);
                for (RepresentmentLetter.ExhibitRow r : letter.evidenceExhibit()) {
                    PdfPCell l = new PdfPCell(new Phrase(r.label(), LABEL));
                    PdfPCell v = new PdfPCell(new Phrase(nvl(r.value()), VALUE));
                    v.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    exhibit.addCell(l);
                    exhibit.addCell(v);
                }
                doc.add(exhibit);
            }

            Paragraph conclusion = new Paragraph(nvl(letter.conclusion()), BOLD);
            conclusion.setSpacingBefore(14);
            doc.add(conclusion);

            Paragraph prov = new Paragraph(nvl(letter.provenance()), SMALL);
            prov.setSpacingBefore(18);
            doc.add(prov);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("representment letter PDF could not be rendered", e);
        }
    }

    private static void headerCell(PdfPTable table, String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.addElement(new Phrase(label, LABEL));
        cell.addElement(new Phrase(nvl(value), VALUE));
        table.addCell(cell);
    }

    private static Paragraph spaced(Paragraph p) {
        p.setSpacingBefore(6);
        return p;
    }

    private static String nvl(String s) {
        return s == null ? "-" : s;
    }

}
