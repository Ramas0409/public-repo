package com.wp.gcp.autocontest.letter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders ONE sample representment letter PDF from dummy data.
 *
 * <pre>
 *   java -cp target/classes:openpdf-1.3.43.jar \
 *        com.wp.gcp.autocontest.letter.SampleLetterPdf [output.pdf]
 * </pre>
 *
 * <p>Nothing here is production code - it exists so a developer can see the artifact before
 * wiring {@link LetterPdfRenderer#render(RepresentmentLetter)} into a real assembler. The dummy
 * letter below is shaped exactly like a live one: opening and argument paragraphs are the only
 * fields a model ever writes, and every number, date and name is a value the caller already
 * holds.
 */
public final class SampleLetterPdf {

    private SampleLetterPdf() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "representment-letter-sample.pdf");

        // THE ONE CALL THAT MATTERS. Same argument as in the orchestrator: the assembled letter.
        byte[] pdf = LetterPdfRenderer.render(dummyLetter());

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, pdf);
        System.out.printf("wrote %s (%d bytes)%n", out.toAbsolutePath(), pdf.length);
    }

    /** A Visa 11.3 (no authorization) letter, populated the way a real FIGHT populates it. */
    private static RepresentmentLetter dummyLetter() {
        return new RepresentmentLetter(
                // letterDate, merchantId, arn, chargebackReason, cardSummary, caseNumber
                "2026-09-01",
                "Northwind Outfitters",
                "74512346123456789012345",
                "Visa 11.3",
                "Visa credit ****4821",
                "POCVISA11304",

                // opening - the validated narrative's own explanation, reused verbatim
                "We are declining this chargeback: the transaction carried a valid authorization "
                        + "approval at the time it was processed, so the condition cited does not "
                        + "apply.",

                // merchantDescription - written from case facts only (name, city, MCC)
                "Northwind Outfitters is an outdoor equipment retailer in Columbus, Ohio, "
                        + "operating under merchant category code 5941.",

                // caseFacts - forwarded numbers, never recomputed
                "The chargeback we are declining was filed on 2026-08-13 for a charge in the "
                        + "amount of 175.25 USD, which was processed on 2026-07-29.",

                // argumentPoints - one per ground of record; the rulebook's words, then the
                // model's paragraph, then the fact keys the ground was decided on
                List.of(
                        new RepresentmentLetter.ArgumentPoint(
                                "1. The Dispute Is Invalid (11.3-G11)",
                                "“A Chargeback under this condition is invalid if the "
                                        + "Transaction was authorized and the Authorization was "
                                        + "not reversed.” — the acquirer's record shows "
                                        + "approval code 04D821 obtained on 29 July 2026 for the "
                                        + "full transaction amount, and no reversal was sent.",
                                List.of("authorization.obtained", "authorization.reversed",
                                        "authorization.approved_amount")),
                        new RepresentmentLetter.ArgumentPoint(
                                "2. Evidence Supporting the Merchant (11.3-G14)",
                                "“The Merchant must provide evidence that the Transaction "
                                        + "amount does not exceed the authorized amount.” "
                                        + "— the approved amount of 175.25 USD equals the "
                                        + "amount cleared, so no partial or incremental "
                                        + "authorization is in question.",
                                List.of("transaction.amount", "authorization.approved_amount"))),

                // evidenceExhibit - the "Transaction Details" block
                List.of(
                        new RepresentmentLetter.ExhibitRow("Amount", "175.25 USD"),
                        new RepresentmentLetter.ExhibitRow("Transaction Date", "2026-07-29"),
                        new RepresentmentLetter.ExhibitRow("Authorization Code", "04D821"),
                        new RepresentmentLetter.ExhibitRow("Authorization Date", "2026-07-29"),
                        new RepresentmentLetter.ExhibitRow("Approved Amount", "175.25"),
                        new RepresentmentLetter.ExhibitRow("Authorization Response",
                                "00 - Approved")),

                // conclusion
                "Due to all of the above information and the evidence presented, the dispute "
                        + "condition cited does not apply to this transaction and the chargeback "
                        + "should be reversed.",

                // provenance - a POC artifact has to say what produced it
                "Generated by WDP auto-contest (POC). Narrative: openai/gpt-5.5. Decided under "
                        + "tree v9 (2026-04 edition), signature status SIGNED.",

                // letterRef, pdfRef, html - set by the store after persistence; unused by the PDF
                null, null, null);
    }

}
