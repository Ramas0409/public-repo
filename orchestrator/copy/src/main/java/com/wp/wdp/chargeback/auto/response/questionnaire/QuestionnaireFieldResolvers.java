package com.wp.wdp.chargeback.auto.response.questionnaire;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.wp.wdp.chargeback.auto.response.dto.llm.CreditDetail;
import com.wp.wdp.chargeback.auto.response.dto.llm.SubFormValue;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseCredit;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.decision.Decision;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireDefinition;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireField;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnaireOption;
import com.wp.wdp.chargeback.auto.response.questionnaire.model.QuestionnairePath;
import com.wp.wdp.chargeback.auto.response.service.decision.ClassifierDecision;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

/** Where each questionnaire field's value comes from: one resolver per field name. */
public final class QuestionnaireFieldResolvers {
    public static final String FULL = "DECL";
    public static final String PART = "PART";
    public static final String CREDIT_PROCESSED_FORM = "CP";
    public static final String USER_ID = "AUTO_CONTEST";

    public static final List<String> NAMES = List.of("responseReason", "prearbitrationReason", "disputeResponse",
            "responseAmount",
            "reasonForNotFullResponsibility", "ctmAmount", "ctmTemplate", "writeOffAmount", "writeOffDirection",
            "writeOffReason", "writeOffNote", "invalidDispute.invalidDisputeReason",
            "invalidDispute.notListedDisputeInvalidReason", "invalidDispute.cardholderRequestedCancellationdate",
            "explanation", "compellingEvidence.evidenceType", "creditProcessed[].creditAmount",
            "creditProcessed[].creditProcessedDate", "creditProcessed[].creditCurrency",
            "creditProcessed[].acquirerReferenceNumber", "comments", "userId");

    public static final List<String> STRUCTURED_PREFIXES = List.of("compellingEvidence.", "remedy.");

    public static final List<String> UNFILLED = List.of("writeOffAmount", "writeOffDirection", "writeOffReason",
            "writeOffNote", "creditProcessed[].acquirerReferenceNumber",
            "invalidDispute.cardholderRequestedCancellationdate");

    static final DateTimeFormatter SENTENCE_DATE = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);
    static final String CREDIT_EXPLANATION = "A credit of {amount} {currency} was processed to the card on {date}; "
            + "the dispute was raised on {disputeDate}. The acquirer asks the issuer to note the credit and close "
            + "the dispute.";
    static final String CREDIT_COVERS = "The credit covers {credit} {currency} of the disputed {disputed} {currency}.";
    static final String PORTION_CONTESTED = "The response contests {contested} {currency} of the disputed {disputed} "
            + "{currency}. {reason}";

    private final Decision decision;
    private final CaseRow row;
    private final QuestionnairePath baseForm;
    private final QuestionnairePath subForm;
    private final String argumentText;
    private final String leadNote;
    private final String reductionSentence;
    private final String ctmTemplate;
    private final String responseType;
    private final Map<String, SubFormValue> structuredValues;
    private final List<QuestionnaireOption> options;
    private final StructuredAnswerFill structuredFill;

    /** {@code reductionSentence} is the register sentence of the row that reduced the contested amount, or null. */
    public QuestionnaireFieldResolvers(Decision decision, CaseRow row, QuestionnairePath baseForm,
                                       QuestionnairePath subForm, String argumentText, String leadNote,
                                       String reductionSentence, String ctmTemplate) {
        this(decision, row, baseForm, subForm, argumentText, leadNote, reductionSentence, ctmTemplate,
                QuestionnaireDefinition.REPRESENTMENT, null, List.of());
    }

    /** The resolvers with the response type, the lead row's structured answer and the value-only options. */
    public QuestionnaireFieldResolvers(Decision decision, CaseRow row, QuestionnairePath baseForm,
                                       QuestionnairePath subForm, String argumentText, String leadNote,
                                       String reductionSentence, String ctmTemplate, String responseType,
                                       Map<String, SubFormValue> structuredValues, List<QuestionnaireOption> options) {
        this.decision = decision;
        this.row = row;
        this.baseForm = baseForm;
        this.subForm = subForm;
        this.argumentText = argumentText;
        this.leadNote = leadNote;
        this.reductionSentence = reductionSentence;
        this.ctmTemplate = ctmTemplate;
        this.responseType = responseType;
        this.structuredValues = structuredValues == null ? Map.of() : structuredValues;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.structuredFill = new StructuredAnswerFill(new QuestionnaireFormatChecks());
    }

    /** Whether the field takes its value from the lead row's structured answer rather than a resolver of its own. */
    public static boolean isStructured(String name) {
        if (NAMES.contains(name)) {
            return false;
        }
        for (String prefix : STRUCTURED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The value of a field as its form declares it, under {@code name}. */
    public Object value(QuestionnaireField field, String name) {
        if (!isStructured(field.getName())) {
            return value(name);
        }
        List<String> choices = new ArrayList<>();
        for (QuestionnaireOption option : options) {
            if (option.getFieldName().equals(field.getName())) {
                choices.add(option.getCode());
            }
        }
        return structuredFill.value(field, name, structuredValues.get(name), choices);
    }

    /** The field's value before shaping. */
    public Object value(String name) {
        switch (name) {
            case "responseReason":
            case "prearbitrationReason":
                return baseForm.getCode();
            case "disputeResponse":
                return disputeResponse();
            case "responseAmount":
                return responseAmount();
            case "reasonForNotFullResponsibility":
                return partial() ? reasonForNotFullResponsibility() : null;
            case "ctmAmount":
                return partial() ? ctmAmount() : null;
            case "ctmTemplate":
                return partial() ? ctmTemplate : null;
            case "writeOffAmount":
            case "writeOffDirection":
            case "writeOffReason":
            case "writeOffNote":
                return null;
            case "invalidDispute.invalidDisputeReason":
            case "compellingEvidence.evidenceType":
                return subForm == null ? null : subForm.getCode();
            case "invalidDispute.notListedDisputeInvalidReason":
                return argumentText;
            case "invalidDispute.cardholderRequestedCancellationdate":
                return null;
            case "explanation":
                return creditForm() ? creditExplanation() : argumentText;
            case "creditProcessed[].creditAmount":
                return creditAmount();
            case "creditProcessed[].creditProcessedDate":
                return creditDate();
            case "creditProcessed[].creditCurrency":
                return creditCurrency();
            case "creditProcessed[].acquirerReferenceNumber":
                return QuestionnaireDefinition.ALLOCATION_PREARBITRATION.equals(responseType) ? creditArn() : null;
            case "comments":
                return leadNote;
            case "userId":
                return USER_ID;
            default:
                throw new ServiceStateException("no resolver for questionnaire field " + name);
        }
    }

    boolean creditForm() {
        return CREDIT_PROCESSED_FORM.equals(baseForm.getCode());
    }

    boolean partial() {
        return PART.equals(disputeResponse());
    }

    String disputeResponse() {
        BigDecimal disputed = decision.getAmounts().getDisputed();
        BigDecimal covered = creditForm() ? creditAmount() : decision.getAmounts().getContested();
        if (disputed == null || covered == null) {
            return null;
        }
        if (creditForm()) {
            return covered.compareTo(disputed) >= 0 ? FULL : PART;
        }
        return covered.compareTo(disputed) == 0 ? FULL : PART;
    }

    BigDecimal responseAmount() {
        String response = disputeResponse();
        if (response == null) {
            return null;
        }
        if (FULL.equals(response)) {
            return decision.getAmounts().getDisputed();
        }
        return creditForm() ? creditAmount() : decision.getAmounts().getContested();
    }

    BigDecimal ctmAmount() {
        BigDecimal disputed = decision.getAmounts().getDisputed();
        BigDecimal response = responseAmount();
        return disputed == null || response == null ? null : disputed.subtract(response);
    }

    String reasonForNotFullResponsibility() {
        BigDecimal disputed = decision.getAmounts().getDisputed();
        String currency = decision.getAmounts().getDisputedCurrency();
        BigDecimal response = responseAmount();
        if (disputed == null || response == null || currency == null) {
            return null;
        }
        List<String> flags = decision.getAmounts().getFlags() == null ? List.of() : decision.getAmounts().getFlags();
        if (creditForm() || flags.contains(ClassifierDecision.PARTIAL_CREDIT)) {
            return CREDIT_COVERS.replace("{credit}", response.toPlainString()).replace("{currency}", currency)
                    .replace("{disputed}", disputed.toPlainString());
        }
        if (reductionSentence == null || reductionSentence.isBlank()) {
            return null;
        }
        return PORTION_CONTESTED.replace("{contested}", response.toPlainString()).replace("{currency}", currency)
                .replace("{disputed}", disputed.toPlainString()).replace("{reason}", reductionSentence);
    }

    BigDecimal creditAmount() {
        CreditDetail credit = decision.getCredit();
        return credit == null ? null : credit.getAmount();
    }

    LocalDate creditDate() {
        CreditDetail credit = decision.getCredit();
        if (credit == null || credit.getDate() == null) {
            return null;
        }
        try {
            return LocalDate.parse(credit.getDate().trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    String creditCurrency() {
        CreditDetail credit = decision.getCredit();
        if (credit == null) {
            return null;
        }
        return credit.getCurrency() == null || credit.getCurrency().isBlank() ? row.getDisputeCurrency()
                : credit.getCurrency();
    }

    String creditArn() {
        BigDecimal amount = creditAmount();
        String currency = creditCurrency();
        if (amount == null || currency == null || row.getCredits() == null) {
            return null;
        }
        List<String> matches = new ArrayList<>();
        for (CaseCredit credit : row.getCredits()) {
            if (credit.getAmount() != null && credit.getAmount().compareTo(amount) == 0
                    && currency.equalsIgnoreCase(credit.getCurrency()) && credit.getArn() != null
                    && !credit.getArn().isBlank()) {
                matches.add(credit.getArn().trim());
            }
        }
        String arn = matches.size() == 1 ? matches.get(0) : null;
        return arn != null && new QuestionnaireFormatChecks().passes("creditProcessed[].acquirerReferenceNumber", arn)
                ? arn : null;
    }

    String creditExplanation() {
        BigDecimal amount = creditAmount();
        LocalDate date = creditDate();
        String currency = creditCurrency();
        if (amount == null || date == null || currency == null || row.getDisputeReportedDate() == null) {
            return null;
        }
        return CREDIT_EXPLANATION.replace("{amount}", amount.toPlainString()).replace("{currency}", currency)
                .replace("{date}", date.format(SENTENCE_DATE))
                .replace("{disputeDate}", row.getDisputeReportedDate().format(SENTENCE_DATE));
    }
}
