package com.wp.wdp.chargeback.auto.response.questionnaire.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.util.LogCapture;
import com.wp.wdp.chargeback.auto.response.dto.llm.NominatedRow;
import com.wp.wdp.chargeback.auto.response.dto.llm.SubFormValue;
import com.wp.wdp.chargeback.auto.response.dto.llm.SupplementaryRow;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseCredit;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFacts;
import com.wp.wdp.chargeback.auto.response.model.check.RecordChecks;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimits;
import com.wp.wdp.chargeback.auto.response.model.decision.Decision;
import com.wp.wdp.chargeback.auto.response.model.decision.Nomination;
import com.wp.wdp.chargeback.auto.response.questionnaire.ClassifierQuestionnaire;
import com.wp.wdp.chargeback.auto.response.questionnaire.dto.VisaAllocationPreArbRequest;
import com.wp.wdp.chargeback.auto.response.questionnaire.dto.VisaQstnnairCollabReprRequest;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireResult;
import com.wp.wdp.chargeback.auto.response.service.Cases;
import com.wp.wdp.chargeback.auto.response.service.SeededRegisterRows;
import com.wp.wdp.chargeback.auto.response.service.TenFourRegister;
import com.wp.wdp.chargeback.auto.response.service.TenFourRegisterRows;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierRecordRules;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierTimeLimits;
import com.wp.wdp.chargeback.auto.response.service.decision.ClassifierDecision;
import com.wp.wdp.chargeback.auto.response.service.register.ArgumentCatalogue;

class ClassifierQuestionnaireImplTest {
    private static final ArgumentCatalogue CATALOGUE = Cases.catalogue();

    private final ClassifierQuestionnaire questionnaires = new ClassifierQuestionnaireImpl(Cases.JSON,
            Cases.registerRows());

    private static Decision decision(CaseRow row, Nomination nomination) {
        IssuerFacts issuer = Cases.bshIssuer();
        return ClassifierDecision.letterMode(ClassifierDecision.decide(row, CATALOGUE, nomination,
                ClassifierTimeLimits.calculate(row, issuer, CATALOGUE), issuer, Cases.records(row, issuer)), List.of());
    }

    /** The body the run built, as the request it was built as. */
    private VisaQstnnairCollabReprRequest saved(CaseRow row, Nomination nomination) throws Exception {
        return Cases.JSON.readValue(generate(row, nomination).getJson(), VisaQstnnairCollabReprRequest.class);
    }

    private QuestionnaireResult generate(CaseRow row, Nomination nomination) {
        IssuerFacts issuer = Cases.bshIssuer();
        TimeLimits limits = ClassifierTimeLimits.calculate(row, issuer, CATALOGUE);
        RecordChecks records = Cases.records(row, issuer);
        return questionnaires.generate(row, decision(row, nomination), CATALOGUE, nomination, limits, records);
    }

    private static Nomination groundLed() {
        return Cases.nomination(Cases.bsh(), List.of(Cases.nominated("13.1-G5", "STRONG")),
                List.of(Cases.supplementary("13.1-G38", "STRONG")), Cases.letter("13.1-G5"));
    }

    private static Nomination preconditionLed(String code) {
        SupplementaryRow lead = Cases.supplementary(code, "STRONG");
        lead.setNote("The filing itself shows it.");
        return Cases.nomination(Cases.bsh(), List.of(), List.of(lead), Cases.letter(code));
    }

    @Test
    @DisplayName("a ground lead files the NL form: the lead's prose, then a fixed sentence for the supplement, never the model's")
    void a_ground_lead_files_not_listed() throws Exception {
        VisaQstnnairCollabReprRequest body = saved(Cases.bsh(), groundLed());

        assertThat(body.getResponseReason()).isEqualTo("NL");
        assertThat(body.getDisputeResponse()).isEqualTo("DECL");
        assertThat(body.getResponseAmount()).isEqualTo(new BigDecimal("25.46"));
        assertThat(body.getInvalidDispute()).isNull();
        assertThat(body.getCreditProcessed()).isNull();
        assertThat(body.getExplanation())
                .startsWith("The merchant contests this dispute on the ground that G5 is met. The records show it.")
                .contains("In addition, the dispute is invalid on this basis: "
                        + CATALOGUE.argument("G38").getLabel() + ".")
                .doesNotContain("G38 applies on the issuer's own filing");
        assertThat(body.getComments()).isEqualTo("The records show G5.");
        assertThat(body.getUserId()).isEqualTo("AUTO_CONTEST");
        assertThat(body.getCtmAmount()).isNull();
        assertThat(body.getCtmTemplate()).isNull();
        assertThat(body.getReasonForNotFullResponsibility()).isNull();
        assertThat(body.getWriteOffAmount()).isNull();
    }

    @Test
    @DisplayName("a precondition lead files ID with its sub-form: IDRC25 carries the argument in notListedDisputeInvalidReason")
    void a_precondition_lead_files_invalid_dispute() throws Exception {
        VisaQstnnairCollabReprRequest body = saved(Cases.bsh(), preconditionLed("13.1-G15"));

        assertThat(body.getResponseReason()).isEqualTo("ID");
        assertThat(body.getInvalidDispute().getInvalidDisputeReason()).isEqualTo("IDRC25");
        assertThat(body.getInvalidDispute().getNotListedDisputeInvalidReason())
                .describedAs("the lead's own prose, its 'In addition,' taken off")
                .isEqualTo("G15 applies on the issuer's own filing.");
        assertThat(body.getExplanation()).isNull();
        assertThat(body.getComments()).isEqualTo("The filing itself shows it.");
        assertThat(Cases.JSON.writeValueAsString(body)).isEqualTo("{\"responseReason\":\"ID\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,"
                + "\"invalidDispute\":{\"invalidDisputeReason\":\"IDRC25\","
                + "\"notListedDisputeInvalidReason\":\"G15 applies on the issuer's own filing.\"},"
                + "\"comments\":\"The filing itself shows it.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @Test
    @DisplayName("G13 selects IDRC4, whose argument is the top-level explanation, as the page serialises it")
    void a_listed_sub_form_carries_the_explanation() throws Exception {
        VisaQstnnairCollabReprRequest body = saved(Cases.bsh(), preconditionLed("13.1-G13"));

        assertThat(body.getInvalidDispute().getInvalidDisputeReason()).isEqualTo("IDRC4");
        assertThat(body.getInvalidDispute().getNotListedDisputeInvalidReason()).isNull();
        assertThat(body.getExplanation()).isEqualTo("G13 applies on the issuer's own filing.");
    }

    @Test
    @DisplayName("a credit after the dispute files CP: the credit as a list of one, its date as the page writes it, the fixed explanation")
    void a_credit_files_credit_processed() throws Exception {
        Nomination nomination = Cases.nomination(Cases.bsh(),
                List.of(Cases.creditNominated("STRONG", "2025-08-05", "25.46", "GBP", "REFUND")),
                List.of(), Cases.letter("13.1-G1"));
        VisaQstnnairCollabReprRequest body = saved(Cases.bsh(), nomination);

        assertThat(body.getResponseReason()).isEqualTo("CP");
        assertThat(body.getDisputeResponse()).isEqualTo("DECL");
        assertThat(body.getResponseAmount()).isEqualTo(new BigDecimal("25.46"));
        assertThat(body.getCreditProcessed()).hasSize(1);
        assertThat(body.getCreditProcessed().get(0).getCreditAmount()).isEqualTo(new BigDecimal("25.46"));
        assertThat(body.getCreditProcessed().get(0).getCreditProcessedDate()).isEqualTo("08/05/2025");
        assertThat(body.getCreditProcessed().get(0).getCreditCurrency()).isEqualTo("GBP");
        assertThat(body.getCreditProcessed().get(0).getAcquirerReferenceNumber()).isNull();
        assertThat(body.getExplanation()).isEqualTo("A credit of 25.46 GBP was processed to the card on 5 August 2025; "
                + "the dispute was raised on 1 August 2025. The acquirer asks the issuer to note the credit and close "
                + "the dispute.");
    }

    @Test
    @DisplayName("a credit that covers part files PART: the credit as the response, the rest charged to the merchant, never written off")
    void a_partial_credit_charges_the_rest_to_the_merchant() throws Exception {
        Nomination nomination = Cases.nomination(Cases.bsh(),
                List.of(Cases.creditNominated("STRONG", "2025-08-05", "10.00", "GBP", "REFUND")),
                List.of(), Cases.letter("13.1-G1"));
        VisaQstnnairCollabReprRequest body = saved(Cases.bsh(), nomination);

        assertThat(body.getDisputeResponse()).isEqualTo("PART");
        assertThat(body.getResponseAmount()).isEqualTo(new BigDecimal("10.00"));
        assertThat(body.getCtmAmount()).isEqualTo(new BigDecimal("15.46"));
        assertThat(body.getCtmTemplate()).isEqualTo("DUMMY_CTM_TEMPLATE");
        assertThat(body.getReasonForNotFullResponsibility())
                .isEqualTo("The credit covers 10.00 GBP of the disputed 25.46 GBP.");
        assertThat(body.getWriteOffAmount()).isNull();
    }

    @Test
    @DisplayName("CL-3023: the step builds and saves nothing: the body as an object and as the JSON the run row keeps")
    void the_body_is_returned_and_never_saved() throws Exception {
        QuestionnaireResult result = generate(Cases.bsh(), groundLed());

        assertThat(result.getBody()).isInstanceOf(VisaQstnnairCollabReprRequest.class);
        assertThat(result.getJson()).isEqualTo(Cases.JSON.writeValueAsString(result.getBody()))
                .contains("\"responseReason\":\"NL\"").contains("\"userId\":\"AUTO_CONTEST\"");
        assertThat(generate(Cases.bsh(), groundLed()).getJson()).describedAs("the same body every time")
                .isEqualTo(result.getJson());
    }

    @Test
    @DisplayName("D2: a decision that files nothing has no questionnaire")
    void no_questionnaire_without_a_filing() {
        Nomination nomination = Cases.nomination(Cases.bsh(), List.of(), List.of(), null);

        QuestionnaireResult result = generate(Cases.bsh(), nomination);
        assertThat(result.getBody()).isNull();
        assertThat(result.getJson()).isNull();
    }

    /** The body a review run of another seeded reason code saves, the register serving that code's own forms (CL-186). */
    private static VisaQstnnairCollabReprRequest savedFor(String reasonCode, Nomination nomination) throws Exception {
        CaseRow row = Cases.withReasonCode(Cases.bsh(), reasonCode);
        ArgumentCatalogue catalogue = Cases.catalogueOfReasonCode(reasonCode);
        IssuerFacts issuer = Cases.issuer();
        TimeLimits limits = ClassifierTimeLimits.calculate(row, issuer, catalogue);
        RecordChecks records = ClassifierRecordRules.calculate(row, issuer, catalogue);
        Decision decision = ClassifierDecision.letterMode(ClassifierDecision.decide(row, catalogue, nomination, limits,
                issuer, records), List.of());
        String json = new ClassifierQuestionnaireImpl(Cases.JSON, new SeededRegisterRows(reasonCode)).generate(row, decision,
                catalogue, nomination, limits, records).getJson();
        return Cases.JSON.readValue(json, VisaQstnnairCollabReprRequest.class);
    }

    private static Nomination groundLedBy(String code) {
        return Cases.nomination(Cases.bsh(), List.of(Cases.nominated(code, "STRONG")), List.of(), Cases.letter(code));
    }

    private static Nomination creditLedBy(String code) {
        NominatedRow credit = Cases.creditNominated("STRONG", "2025-08-05", "25.46", "GBP", "REFUND");
        credit.setCode(code);
        return Cases.nomination(Cases.bsh(), List.of(credit), List.of(), Cases.letter(code));
    }

    @Test
    @DisplayName("CL-187: 12.5's ID has no sub-form and no invalidDispute block; the argument is the explanation, the note the comments")
    void twelve_five_invalid_dispute_carries_the_argument_in_explanation() throws Exception {
        VisaQstnnairCollabReprRequest body = savedFor("12.5", preconditionLed("12.5-G5"));

        assertThat(body.getResponseReason()).isEqualTo("ID");
        assertThat(body.getInvalidDispute()).isNull();
        assertThat(Cases.JSON.writeValueAsString(body)).isEqualTo("{\"responseReason\":\"ID\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,"
                + "\"explanation\":\"G5 applies on the issuer's own filing.\","
                + "\"comments\":\"The filing itself shows it.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @Test
    @DisplayName("CL-186: a 12.5 ground keeps its form: NL, the argument in explanation and the model's note in comments")
    void twelve_five_ground_keeps_its_note() throws Exception {
        VisaQstnnairCollabReprRequest body = savedFor("12.5", groundLedBy("12.5-G14"));

        assertThat(body.getResponseReason()).isEqualTo("NL");
        assertThat(body.getExplanation()).startsWith("The merchant contests this dispute on the ground that G14 is met.");
        assertThat(body.getComments()).isEqualTo("The records show G14.");
    }

    @ParameterizedTest(name = "{0}-{1} files ID / {2}")
    @CsvSource({"13.7, G7, IDRC23", "13.7, G8, IDRC9", "13.7, G9, IDRC10", "13.7, G16, IDRC24", "13.3, G23, IDRC24"})
    @DisplayName("CL-186: a precondition matching one of its code's listed reasons selects it, its argument the top-level explanation")
    void a_listed_reason_carries_the_explanation(String reasonCode, String row, String subForm) throws Exception {
        VisaQstnnairCollabReprRequest body = savedFor(reasonCode, preconditionLed(reasonCode + "-" + row));

        assertThat(body.getResponseReason()).isEqualTo("ID");
        assertThat(body.getInvalidDispute().getInvalidDisputeReason()).isEqualTo(subForm);
        assertThat(body.getInvalidDispute().getNotListedDisputeInvalidReason()).isNull();
        assertThat(body.getExplanation()).isEqualTo(row + " applies on the issuer's own filing.");
        assertThat(body.getComments()).isEqualTo("The filing itself shows it.");
    }

    @ParameterizedTest(name = "{0}-{1} files ID / IDRC25")
    @CsvSource({"13.7, G15", "13.2, G5", "13.3, G7"})
    @DisplayName("CL-186: every other precondition stays IDRC25, its argument in notListedDisputeInvalidReason")
    void every_other_precondition_stays_not_listed(String reasonCode, String row) throws Exception {
        VisaQstnnairCollabReprRequest body = savedFor(reasonCode, preconditionLed(reasonCode + "-" + row));

        assertThat(body.getInvalidDispute().getInvalidDisputeReason()).isEqualTo("IDRC25");
        assertThat(body.getInvalidDispute().getNotListedDisputeInvalidReason())
                .isEqualTo(row + " applies on the issuer's own filing.");
        assertThat(body.getInvalidDispute().getCardholderRequestedCancellationdate()).isNull();
        assertThat(body.getExplanation()).isNull();
        assertThat(body.getComments()).isEqualTo("The filing itself shows it.");
    }

    @ParameterizedTest(name = "{0}: CP {1}, CNLD {2}, NL {3}")
    @CsvSource({"13.2, G1, G11, G12", "13.3, G1, G2, G3", "13.7, G1, G2, G3", "12.5, G1, G13, G14"})
    @DisplayName("CL-186: each code's grounds keep their base forms, with no sub-form, even where a listed reason matches")
    void grounds_keep_their_base_forms(String reasonCode, String credit, String withdrew, String notListed)
            throws Exception {
        VisaQstnnairCollabReprRequest creditBody = savedFor(reasonCode, creditLedBy(reasonCode + "-" + credit));
        assertThat(creditBody.getResponseReason()).isEqualTo("CP");
        assertThat(creditBody.getCreditProcessed()).hasSize(1);
        assertThat(creditBody.getExplanation()).startsWith("A credit of 25.46 GBP was processed");
        assertThat(creditBody.getInvalidDispute()).isNull();

        VisaQstnnairCollabReprRequest withdrewBody = savedFor(reasonCode, groundLedBy(reasonCode + "-" + withdrew));
        assertThat(withdrewBody.getResponseReason()).isEqualTo("CNLD");
        assertThat(withdrewBody.getExplanation()).startsWith("The merchant contests this dispute on the ground that "
                + withdrew + " is met.");
        assertThat(withdrewBody.getInvalidDispute()).isNull();

        VisaQstnnairCollabReprRequest groundBody = savedFor(reasonCode, groundLedBy(reasonCode + "-" + notListed));
        assertThat(groundBody.getResponseReason()).isEqualTo("NL");
        assertThat(groundBody.getComments()).isEqualTo("The records show " + notListed + ".");
        assertThat(groundBody.getInvalidDispute()).isNull();
    }

    // ---- CL-1001: 10.4 files on allocprearb; one exact-JSON test per form, on the synthetic register of the pack ----

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static CaseRow tenFour() {
        return Cases.withReasonCode(Cases.bsh(), "10.4");
    }

    /** The allocprearb body a review run of 10.4 saves on the register named, checked to be saved as allocprearb. */
    private static String savedTenFour(String register, CaseRow row, Nomination nomination) {
        ArgumentCatalogue catalogue = TenFourRegister.catalogue(register);
        IssuerFacts issuer = Cases.issuer();
        TimeLimits limits = ClassifierTimeLimits.calculate(row, issuer, catalogue);
        RecordChecks records = ClassifierRecordRules.calculate(row, issuer, catalogue);
        Decision decision = ClassifierDecision.letterMode(ClassifierDecision.decide(row, catalogue, nomination, limits,
                issuer, records), List.of());
        QuestionnaireResult result = new ClassifierQuestionnaireImpl(Cases.JSON, new TenFourRegisterRows(register))
                .generate(row, decision, catalogue, nomination, limits, records);
        assertThat(result.getBody()).isInstanceOf(com.wp.wdp.chargeback.auto.response.questionnaire.dto.VisaAllocationPreArbRequest.class);
        return result.getJson();
    }

    private static String savedTenFour(String register, Nomination nomination) {
        return savedTenFour(register, tenFour(), nomination);
    }

    private static Nomination tenFourGround(String code, Map<String, SubFormValue> values) {
        NominatedRow lead = Cases.nominated("10.4-" + code, "STRONG");
        lead.setSubFormValues(values);
        return Cases.nomination(tenFour(), List.of(lead), List.of(), Cases.letter("10.4-" + code));
    }

    private static Nomination tenFourCredit(String amount) {
        NominatedRow credit = Cases.creditNominated("STRONG", "2025-08-05", amount, "GBP", "REFUND");
        credit.setCode("10.4-G1");
        return Cases.nomination(tenFour(), List.of(credit), List.of(), Cases.letter("10.4-G1"));
    }

    private static SubFormValue value(JsonNode value, String printed) {
        SubFormValue out = new SubFormValue();
        out.setValue(value);
        out.setPrinted(printed);
        out.setDocumentRef("POCVISA13117/merchant/merchant.pdf");
        out.setPage(2);
        out.setQuote(printed == null ? value.asText() : printed);
        return out;
    }

    private static SubFormValue text(String text) {
        return value(NODES.textNode(text), null);
    }

    private static SubFormValue printed(String value, String printed) {
        return value(NODES.textNode(value), printed);
    }

    private static SubFormValue amount(String value, String printed) {
        return value(NODES.numberNode(new BigDecimal(value)), printed);
    }

    private static SubFormValue yes() {
        return value(NODES.booleanNode(true), null);
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: ND - the cardholder no longer disputes; the reason, the amount, the note and nothing else")
    void ten_four_files_no_longer_disputes(String register) {
        assertThat(savedTenFour(register, tenFourGround("G2", null))).isEqualTo("{\"prearbitrationReason\":\"ND\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"comments\":\"The records show G2.\","
                + "\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: CP in full - the credit as MultipleCredits; the ARN only from the one request credit of the amount")
    void ten_four_files_credit_processed_in_full(String register) {
        assertThat(savedTenFour(register, tenFourCredit("25.46"))).isEqualTo("{\"prearbitrationReason\":\"CP\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"creditProcessed\":[{\"creditAmount\":25.46,"
                + "\"creditProcessedDate\":\"08/05/2025\",\"creditCurrency\":\"GBP\"}],"
                + "\"comments\":\"The records show G1.\",\"userId\":\"AUTO_CONTEST\"}");
        CaseRow credited = Cases.withCredits(tenFour(), List.of(new CaseCredit("74838565156920021009999",
                LocalDate.of(2025, 8, 5), new BigDecimal("25.46"), "GBP")));
        assertThat(savedTenFour(register, credited, tenFourCredit("25.46")))
                .contains("\"creditCurrency\":\"GBP\",\"acquirerReferenceNumber\":\"74838565156920021009999\"}]");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("P23: the CP ARN is left blank where two credits match, where the amount is a total, or with no ARN")
    void ten_four_credit_arn_only_from_one_credit(String register) {
        CaseRow twoOfTheAmount = Cases.withCredits(tenFour(), List.of(
                new CaseCredit("74838565156920021000001", LocalDate.of(2025, 8, 5), new BigDecimal("12.73"), "GBP"),
                new CaseCredit("74838565156920021000002", LocalDate.of(2025, 8, 6), new BigDecimal("12.73"), "GBP")));
        CaseRow aTotal = Cases.withCredits(tenFour(), List.of(
                new CaseCredit("74838565156920021000001", LocalDate.of(2025, 8, 5), new BigDecimal("10.00"), "GBP"),
                new CaseCredit("74838565156920021000002", LocalDate.of(2025, 8, 6), new BigDecimal("15.46"), "GBP")));
        CaseRow noArn = Cases.withCredits(tenFour(), List.of(
                new CaseCredit(null, LocalDate.of(2025, 8, 5), new BigDecimal("25.46"), "GBP")));
        for (CaseRow row : List.of(twoOfTheAmount, aTotal, noArn)) {
            assertThat(savedTenFour(register, row, tenFourCredit("25.46"))).contains("\"creditProcessed\":[{")
                    .doesNotContain("acquirerReferenceNumber");
        }
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: CP in part - the CTM amount and template beside the credit")
    void ten_four_files_credit_processed_in_part(String register) {
        assertThat(savedTenFour(register, tenFourCredit("10.00"))).isEqualTo("{\"prearbitrationReason\":\"CP\","
                + "\"disputeResponse\":\"PART\",\"responseAmount\":10.00,\"ctmAmount\":15.46,"
                + "\"ctmTemplate\":\"DUMMY_CTM_TEMPLATE\",\"creditProcessed\":[{\"creditAmount\":10.00,"
                + "\"creditProcessedDate\":\"08/05/2025\",\"creditCurrency\":\"GBP\"}],"
                + "\"comments\":\"The records show G1.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: ID / IDRA7 - a precondition's argument in notListedDisputeInvalidReason, its note in comments")
    void ten_four_files_invalid_dispute(String register) {
        assertThat(savedTenFour(register, preconditionLed("10.4-G18"))).isEqualTo("{\"prearbitrationReason\":\"ID\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"invalidDispute\":{"
                + "\"invalidDisputeReason\":\"IDRA7\",\"notListedDisputeInvalidReason\":\"G18 applies on the issuer's "
                + "own filing.\"},\"comments\":\"The filing itself shows it.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: a CE type with no fields carries its evidence type alone")
    void ten_four_files_a_field_less_evidence_type(String register) {
        assertThat(savedTenFour(register, tenFourGround("G30", null))).isEqualTo("{\"prearbitrationReason\":\"CE\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"compellingEvidence\":{"
                + "\"evidenceType\":\"CE01\"},\"comments\":\"The records show G30.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: CE03 - the proof of delivery from the lead's structured answer; a CE03 with nothing found is the type alone")
    void ten_four_files_proof_of_delivery(String register) {
        Map<String, SubFormValue> values = new LinkedHashMap<>();
        values.put("compellingEvidence.proofOfDelivery.name", text("J Example"));
        values.put("compellingEvidence.proofOfDelivery.address", text("1 Test Street"));
        values.put("compellingEvidence.proofOfDelivery.address2", text("Flat 2"));
        values.put("compellingEvidence.proofOfDelivery.city", text("Leeds"));
        values.put("compellingEvidence.proofOfDelivery.zipCode", text("LS1 4AB"));
        values.put("compellingEvidence.proofOfDelivery.country", printed("GBR", "United Kingdom"));
        values.put("compellingEvidence.proofOfDelivery.shippingCompany", printed("UPS", "UPS"));
        values.put("compellingEvidence.proofOfDelivery.trackingNumber", text("1Z999AA10123456784"));
        assertThat(savedTenFour(register, tenFourGround("G32", values))).isEqualTo("{\"prearbitrationReason\":\"CE\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"compellingEvidence\":{"
                + "\"evidenceType\":\"CE03\",\"proofOfDelivery\":{\"name\":\"J Example\",\"address\":\"1 Test Street\","
                + "\"address2\":\"Flat 2\",\"city\":\"Leeds\",\"zipCode\":\"LS1 4AB\",\"country\":\"GBR\","
                + "\"shippingCompany\":\"UPS\",\"trackingNumber\":\"1Z999AA10123456784\"}},"
                + "\"comments\":\"The records show G32.\",\"userId\":\"AUTO_CONTEST\"}");
        assertThat(savedTenFour(register, tenFourGround("G32", null))).contains("\"compellingEvidence\":{\"evidenceType\":\"CE03\"},");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: CE04 - the date-time as the page saves it, and all six indicators, false where not shown")
    void ten_four_files_digital_download(String register) {
        Map<String, SubFormValue> values = new LinkedHashMap<>();
        values.put("compellingEvidence.digitalDownloadEvidence.downloadDescription", text("An e-book download"));
        values.put("compellingEvidence.digitalDownloadEvidence.downloadDateAndTime",
                printed("2025-06-03T14:30", "03 Jun 2025 14:30"));
        values.put("compellingEvidence.digitalDownloadEvidence.purchaserIPAddressInd", yes());
        values.put("compellingEvidence.digitalDownloadEvidence.ipAddress", text("203.0.113.7"));
        values.put("compellingEvidence.digitalDownloadEvidence.websitePurchaseInd", yes());
        assertThat(savedTenFour(register, tenFourGround("G33", values))).isEqualTo("{\"prearbitrationReason\":\"CE\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"compellingEvidence\":{"
                + "\"evidenceType\":\"CE04\",\"digitalDownloadEvidence\":{\"downloadDescription\":\"An e-book download\","
                + "\"downloadDateAndTime\":\"06/03/2025-14:30\",\"purchaserIPAddressInd\":true,"
                + "\"ipAddress\":\"203.0.113.7\",\"deviceIdAndNameInd\":false,\"purchaserNameAndEmailInd\":false,"
                + "\"purchaserProfileVerificationInd\":false,\"deviceAndCardUsedInUndisputedTransInd\":false,"
                + "\"websitePurchaseInd\":true}},\"comments\":\"The records show G33.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: CE09 - the six flags always sent, and the undisputed payment")
    void ten_four_files_non_disputed_payment(String register) {
        Map<String, SubFormValue> values = new LinkedHashMap<>();
        values.put("compellingEvidence.nonDisputedPayment.ipAddress", yes());
        values.put("compellingEvidence.nonDisputedPayment.emailAddress", yes());
        values.put("compellingEvidence.nonDisputedPayment.arn", text("74838565156920021000003"));
        values.put("compellingEvidence.nonDisputedPayment.transactionAmount", amount("25.46", "£25.46"));
        values.put("compellingEvidence.nonDisputedPayment.transactionDate", printed("2025-05-01", "1 May 2025"));
        values.put("compellingEvidence.nonDisputedPayment.currency", printed("GBP", "GBP"));
        assertThat(savedTenFour(register, tenFourGround("G39", values))).isEqualTo("{\"prearbitrationReason\":\"CE\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"compellingEvidence\":{"
                + "\"evidenceType\":\"CE09\",\"nonDisputedPayment\":{\"ipAddress\":true,\"emailAddress\":true,"
                + "\"physicalAddress\":false,\"phoneNumber\":false,\"custAccLoginId\":false,\"deviceIdFingerPrint\":false,"
                + "\"arn\":\"74838565156920021000003\",\"transactionAmount\":25.46,\"transactionDate\":\"05/01/2025\","
                + "\"currency\":\"GBP\"}},\"comments\":\"The records show G39.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: CE13 - the earlier recurring transaction under recuringTrans, as the page spells it")
    void ten_four_files_recurring_transaction(String register) {
        Map<String, SubFormValue> values = new LinkedHashMap<>();
        values.put("compellingEvidence.recuringTrans.arn", text("74838565156920021000004"));
        values.put("compellingEvidence.recuringTrans.transactionAmount", amount("9.99", "9.99 GBP"));
        values.put("compellingEvidence.recuringTrans.transactionDate", printed("2025-05-03", "2025-05-03"));
        values.put("compellingEvidence.recuringTrans.currency", printed("GBP", "GBP"));
        assertThat(savedTenFour(register, tenFourGround("G42", values))).isEqualTo("{\"prearbitrationReason\":\"CE\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"compellingEvidence\":{"
                + "\"evidenceType\":\"CE13\",\"recuringTrans\":{\"arn\":\"74838565156920021000004\","
                + "\"transactionAmount\":9.99,\"transactionDate\":\"05/03/2025\",\"currency\":\"GBP\"}},"
                + "\"comments\":\"The records show G42.\",\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: CE16 - the destination wallet and the transaction hash")
    void ten_four_files_crypto(String register) {
        Map<String, SubFormValue> values = new LinkedHashMap<>();
        values.put("compellingEvidence.nonFiatCurrencyNonFungibleToken.destinationWalletAddress",
                text("bc1qexamplewalletaddress0000"));
        values.put("compellingEvidence.nonFiatCurrencyNonFungibleToken.blockchainTransactionHash", text("0xexamplehash"));
        assertThat(savedTenFour(register, tenFourGround("G44", values))).isEqualTo("{\"prearbitrationReason\":\"CE\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"compellingEvidence\":{"
                + "\"evidenceType\":\"CE16\",\"nonFiatCurrencyNonFungibleToken\":{"
                + "\"destinationWalletAddress\":\"bc1qexamplewalletaddress0000\","
                + "\"blockchainTransactionHash\":\"0xexamplehash\"}},\"comments\":\"The records show G44.\","
                + "\"userId\":\"AUTO_CONTEST\"}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("P17: RE - the remedy with two undisputed transactions always, one found empty")
    void ten_four_files_compelling_evidence_three(String register) {
        String item = "remedy.undisputedTransactions.undisputedTransaction[0].";
        Map<String, SubFormValue> values = new LinkedHashMap<>();
        values.put("remedy.merchandiseOrServices", printed("Merchandise", "Merchandise"));
        values.put("remedy.merchandiseOrServicesProvidedDate", printed("2025-06-04", "4 June 2025"));
        values.put("remedy.merchandiseOrServicesDesc", text("A synthetic kettle"));
        values.put("remedy.disputedTransaction.ipAddressDetails", text("203.0.113.7"));
        values.put("remedy.disputedTransaction.deliveryAddressDetails.country", printed("826", "United Kingdom"));
        values.put(item + "historicalARN", text("748385651569200210000050"));
        values.put(item + "historicalTranAmount.value", amount("19.99", "£19.99"));
        values.put(item + "historicalTranAmount.currency", printed("GBP", "GBP"));
        values.put(item + "historicalTranDate", printed("2025-01-10", "10 January 2025"));
        values.put(item + "merchandiseOrServicesDesc", text("A synthetic toaster"));
        values.put(item + "ipAddressDetails", text("203.0.113.7"));
        assertThat(savedTenFour(register, tenFourGround("G7", values))).isEqualTo("{\"prearbitrationReason\":\"RE\","
                + "\"disputeResponse\":\"DECL\",\"responseAmount\":25.46,\"remedy\":{"
                + "\"merchandiseOrServices\":\"Merchandise\",\"merchandiseOrServicesProvidedDate\":\"06/04/2025\","
                + "\"merchandiseOrServicesDesc\":\"A synthetic kettle\",\"disputedTransaction\":{"
                + "\"ipAddressDetails\":\"203.0.113.7\",\"deliveryAddressDetails\":{\"country\":\"826\"}},"
                + "\"undisputedTransactions\":{\"undisputedTransaction\":[{\"historicalARN\":\"748385651569200210000050\","
                + "\"historicalTranAmount\":{\"value\":19.99,\"currency\":\"GBP\"},\"historicalTranDate\":\"01/10/2025\","
                + "\"merchandiseOrServicesDesc\":\"A synthetic toaster\",\"ipAddressDetails\":\"203.0.113.7\"},{}]}},"
                + "\"comments\":\"The records show G7.\",\"userId\":\"AUTO_CONTEST\"}");
        assertThat(savedTenFour(register, tenFourGround("G7", null))).contains("\"remedy\":{\"undisputedTransactions\":"
                + "{\"undisputedTransaction\":[{},{}]}}");
    }

    @ParameterizedTest(name = "{0} register")
    @ValueSource(strings = {TenFourRegister.PACK, TenFourRegister.SEEDED})
    @DisplayName("CL-1001: only the lead's values are filed - a supplement's never reach the body")
    void only_the_leads_values_are_filed(String register) {
        NominatedRow lead = Cases.nominated("10.4-G30", "STRONG");
        NominatedRow other = Cases.nominated("10.4-G32", "PARTIAL");
        other.setSubFormValues(Map.of("compellingEvidence.proofOfDelivery.city", text("Leeds")));
        Nomination nomination = Cases.nomination(tenFour(), List.of(lead, other), List.of(),
                Cases.letter("10.4-G30", "10.4-G32"));
        assertThat(savedTenFour(register, nomination)).contains("\"compellingEvidence\":{\"evidenceType\":\"CE01\"}")
                .doesNotContain("Leeds");
    }

    @Test
    @DisplayName("a body that cannot be written as JSON is one WARN, event=questionnaire.failed, with the log-safe trace")
    void a_body_not_written_logs_its_trace() {
        ClassifierQuestionnaire failing = new ClassifierQuestionnaireImpl(new FailingWriteMapper(), Cases.registerRows());
        CaseRow row = Cases.bsh();
        Nomination nomination = groundLed();
        IssuerFacts issuer = Cases.bshIssuer();
        try (LogCapture logs = LogCapture.open()) {
            QuestionnaireResult result = failing.generate(row, decision(row, nomination), CATALOGUE, nomination,
                    ClassifierTimeLimits.calculate(row, issuer, CATALOGUE), Cases.records(row, issuer));
            assertThat(result.getJson()).isNull();
            assertThat(logs.withEvent("questionnaire.failed")).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.WARN);
                assertThat(line.getFormattedMessage()).isEqualTo("event=questionnaire.failed error=JsonMappingException");
            });
            assertThat(logs.printedAll()).contains("JsonMappingException").doesNotContain(FailingWriteMapper.QUOTE);
        }
    }
}
