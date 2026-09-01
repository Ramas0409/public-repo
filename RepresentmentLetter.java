package com.wp.gcp.autocontest.letter;

import java.util.List;

/**
 * The representment letter as DATA - the audit object the PDF is rendered from.
 *
 * <p>HANDOVER COPY. Field order, names and accessor signatures are IDENTICAL to the orchestrator's
 * {@code com.wp.gcp.autocontest.letter.RepresentmentLetter}; only Lombok is gone, so this package
 * compiles with nothing but OpenPDF on the classpath. Swap either class for the other freely.
 *
 * <p><strong>Assembled, never written.</strong> Every section is a rendering of an artifact that
 * already exists by the time the letter is built: the opening is the validated narrative the
 * filing carries, the argument points are the grounds of record, the exhibit rows are forwarded
 * numbers - so the letter and the filed questionnaire provably say the same thing.
 */
public final class RepresentmentLetter {

    private final String letterDate;
    private final String merchantId;
    private final String arn;
    private final String chargebackReason;
    private final String cardSummary;
    private final String caseNumber;
    /** The validated narrative's explanation - the filing's own prose, reused verbatim. */
    private final String opening;
    /** The merchant described from case facts only - same call, same validation. */
    private final String merchantDescription;
    /** Filed/processed dates and amounts, forwarded from the case - never recomputed. */
    private final String caseFacts;
    private final List<ArgumentPoint> argumentPoints;
    /** "Transaction Details" exhibit rows - the evidence block. */
    private final List<ExhibitRow> evidenceExhibit;
    private final String conclusion;
    /** The provenance footer: which model wrote the prose, which signed tree decided. */
    private final String provenance;
    /** Store reference of the HTML rendering, null where persistence failed. */
    private final String letterRef;
    /** Store reference of the PDF rendering - THE ARTIFACT THE NETWORKS ACCEPT. */
    private final String pdfRef;
    /** The rendered HTML, inline, so the caller needs no second fetch. */
    private final String html;

    public RepresentmentLetter(String letterDate, String merchantId, String arn,
                               String chargebackReason, String cardSummary, String caseNumber,
                               String opening, String merchantDescription, String caseFacts,
                               List<ArgumentPoint> argumentPoints, List<ExhibitRow> evidenceExhibit,
                               String conclusion, String provenance, String letterRef,
                               String pdfRef, String html) {
        this.letterDate = letterDate;
        this.merchantId = merchantId;
        this.arn = arn;
        this.chargebackReason = chargebackReason;
        this.cardSummary = cardSummary;
        this.caseNumber = caseNumber;
        this.opening = opening;
        this.merchantDescription = merchantDescription;
        this.caseFacts = caseFacts;
        this.argumentPoints = argumentPoints == null ? List.of() : List.copyOf(argumentPoints);
        this.evidenceExhibit = evidenceExhibit == null ? List.of() : List.copyOf(evidenceExhibit);
        this.conclusion = conclusion;
        this.provenance = provenance;
        this.letterRef = letterRef;
        this.pdfRef = pdfRef;
        this.html = html;
    }

    public RepresentmentLetter withRefs(String htmlRef, String pdfReference) {
        return new RepresentmentLetter(letterDate, merchantId, arn, chargebackReason, cardSummary,
                caseNumber, opening, merchantDescription, caseFacts, argumentPoints,
                evidenceExhibit, conclusion, provenance, htmlRef, pdfReference, html);
    }

    public String letterDate() {
        return letterDate;
    }

    public String merchantId() {
        return merchantId;
    }

    public String arn() {
        return arn;
    }

    public String chargebackReason() {
        return chargebackReason;
    }

    public String cardSummary() {
        return cardSummary;
    }

    public String caseNumber() {
        return caseNumber;
    }

    public String opening() {
        return opening;
    }

    public String merchantDescription() {
        return merchantDescription;
    }

    public String caseFacts() {
        return caseFacts;
    }

    public List<ArgumentPoint> argumentPoints() {
        return argumentPoints;
    }

    public List<ExhibitRow> evidenceExhibit() {
        return evidenceExhibit;
    }

    public String conclusion() {
        return conclusion;
    }

    public String provenance() {
        return provenance;
    }

    public String letterRef() {
        return letterRef;
    }

    public String pdfRef() {
        return pdfRef;
    }

    public String html() {
        return html;
    }

    /** One ground of record, argued. */
    public static final class ArgumentPoint {

        private final String title;
        private final String body;
        /** The fact keys the ground was decided on - the letter's claim is checkable from data. */
        private final List<String> decidedOn;

        public ArgumentPoint(String title, String body, List<String> decidedOn) {
            this.title = title;
            this.body = body;
            this.decidedOn = decidedOn == null ? List.of() : List.copyOf(decidedOn);
        }

        public String title() {
            return title;
        }

        public String body() {
            return body;
        }

        public List<String> decidedOn() {
            return decidedOn;
        }
    }

    /** One label/value row of the transaction-details exhibit. */
    public static final class ExhibitRow {

        private final String label;
        private final String value;

        public ExhibitRow(String label, String value) {
            this.label = label;
            this.value = value;
        }

        public String label() {
            return label;
        }

        public String value() {
            return value;
        }
    }

}
