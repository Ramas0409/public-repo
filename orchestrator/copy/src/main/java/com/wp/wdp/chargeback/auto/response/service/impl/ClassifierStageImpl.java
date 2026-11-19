package com.wp.wdp.chargeback.auto.response.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.wp.wdp.chargeback.auto.response.dto.llm.ScenarioMerchantDocument;
import com.wp.wdp.chargeback.auto.response.dto.llm.ScenarioMerchantDocumentType;
import com.wp.wdp.chargeback.auto.response.model.register.MerchantDocumentType;
import com.wp.wdp.chargeback.auto.response.dto.llm.NominateResponse;
import com.wp.wdp.chargeback.auto.response.dto.llm.ScenarioDocument;
import com.wp.wdp.chargeback.auto.response.dto.llm.ScenarioPreviousAsk;
import com.wp.wdp.chargeback.auto.response.dto.run.MerchantAskRecord;
import com.wp.wdp.chargeback.auto.response.dto.llm.ScenarioFact;
import com.wp.wdp.chargeback.auto.response.dto.llm.ScenarioRequest;
import com.wp.wdp.chargeback.auto.response.dto.llm.ScenarioRoles;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseCredit;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseDocument;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFact;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFacts;
import com.wp.wdp.chargeback.auto.response.model.check.RecordCheckResult;
import com.wp.wdp.chargeback.auto.response.model.check.RecordChecks;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimitRow;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimits;
import com.wp.wdp.chargeback.auto.response.model.decision.Nomination;
import com.wp.wdp.chargeback.auto.response.model.decision.SecondRun;
import com.wp.wdp.chargeback.auto.response.model.decision.RenderedPayload;
import com.wp.wdp.chargeback.auto.response.model.register.RowRoles;
import com.wp.wdp.chargeback.auto.response.service.CaseRecordTokens;
import com.wp.wdp.chargeback.auto.response.service.ClassifierStage;
import com.wp.wdp.chargeback.auto.response.service.LlmClient;
import com.wp.wdp.chargeback.auto.response.service.check.ClassifierRecordRules;
import com.wp.wdp.chargeback.auto.response.service.check.RecordCheck;
import com.wp.wdp.chargeback.auto.response.service.check.RecordMapping;
import com.wp.wdp.chargeback.auto.response.service.check.TimeLimitCalculator;
import com.wp.wdp.chargeback.auto.response.service.register.ArgumentCatalogue;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

@RequiredArgsConstructor
@Component
public class ClassifierStageImpl implements ClassifierStage {
    private static final Logger log = LoggerFactory.getLogger(ClassifierStage.class);

    private final LlmClient llmClient;
    private final CaseRecordTokens tokens;
    private final ObjectMapper objectMapper;

    @Override
    public ScenarioRequest request(CaseRow row, ArgumentCatalogue catalogue,
                                             RenderedPayload payload,
                                             IssuerFacts issuer,
                                             TimeLimits limits,
                                             RecordChecks records,
                                             SecondRun secondRun) {
        List<ScenarioDocument> documents = new ArrayList<>();

        for (CaseDocument document : row.merchantDocuments()) {
            documents.add(document(document, secondRun));
        }
        for (CaseDocument document : row.issuerDocuments()) {
            documents.add(document(document, secondRun));
        }

        List<Map<String, Object>> timeLimitMaps =
                TimeLimitCalculator.asMaps(limits.rendered(), catalogue);
        ScenarioRequest request = new ScenarioRequest(
                row.getCaseNumber(), row.getAcquiringPlatform(), catalogue.reasonCode(), documents, caseRecord(row),
                facts(row, issuer), payload.getPayload(), payload.getDigest(), payload.getGroundCodes(),
                payload.getSupplementaryCodes(), timeLimitMaps,

                !row.merchantDocuments().isEmpty(),
                List.of(),
                ClassifierRecordRules.asMaps(records.rendered(), catalogue));
        request.setNetwork(catalogue.network());
        request.setReasonCodeName(catalogue.reasonCodeName());
        request.setNetworkName(catalogue.networkName());
        request.setNeverMention(catalogue.neverMention());
        request.setBannedPhrases(catalogue.bannedPhrases());
        request.setRoles(new ScenarioRoles(catalogue.rowWithRole(RowRoles.CREDIT),
                catalogue.rowWithRole(RowRoles.FRAUD_CLAIM), catalogue.rowsWithRole(RowRoles.POLICY)));
        request.setSubFormFields(payload.getSubFormFields());
        if (!catalogue.merchantDocumentTypes().isEmpty()) {
            request.setMerchantDocumentTypes(merchantDocumentTypes(catalogue));
            request.setMerchantDocuments(merchantDocuments(catalogue, payload));
        }
        if (secondRun != null) {
            request.setPreviousAsks(previousAsks(secondRun));
        }
        return request;
    }

    /** The code's merchant-document vocabulary, in the register's order. */
    static List<ScenarioMerchantDocumentType> merchantDocumentTypes(ArgumentCatalogue catalogue) {
        List<ScenarioMerchantDocumentType> out = new ArrayList<>();
        for (MerchantDocumentType type : catalogue.merchantDocumentTypes()) {
            out.add(new ScenarioMerchantDocumentType(type.getKey(), type.getLabel(), type.getAsk()));
        }
        return out;
    }

    /** Each rendered argument's documents by its qualified code, in the argument list's order; rows with none left out. */
    static Map<String, List<ScenarioMerchantDocument>> merchantDocuments(ArgumentCatalogue catalogue,
                                                                        RenderedPayload payload) {
        Map<String, List<ScenarioMerchantDocument>> out = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>(payload.getGroundCodes());
        codes.addAll(payload.getSupplementaryCodes());
        for (String code : codes) {
            List<ScenarioMerchantDocument> documents = new ArrayList<>();
            for (String key : catalogue.merchantDocuments(code)) {
                MerchantDocumentType type = catalogue.merchantDocumentType(key);
                if (type != null) {
                    documents.add(new ScenarioMerchantDocument(type.getKey(), type.getLabel()));
                }
            }
            if (!documents.isEmpty()) {
                out.put(code, documents);
            }
        }
        return out;
    }

    @Override
    public Nomination nominate(CaseRow row, ArgumentCatalogue catalogue,
                               RenderedPayload payload,
                               IssuerFacts issuer, TimeLimits limits,
                               RecordChecks records, SecondRun secondRun) {
        ScenarioRequest request = request(row, catalogue, payload, issuer, limits, records, secondRun);
        Map<String, Object> caseRecord = request.getCaseRecord();

        JsonNode answer;
        try {
            answer = llmClient.nominate(request);
        } catch (RuntimeException exception) {
            log.warn("event=nominate.failed error={}", exception.getClass().getSimpleName(), LogSafe.of(exception));

            return new Nomination(ERROR, new NominateResponse(), objectMapper.createObjectNode(), null,
                    exception.toString(), caseRecord);
        }
        return read(row, answer, limits, records, caseRecord);
    }

    @Override
    public Nomination read(CaseRow row, JsonNode answer, TimeLimits limits, RecordChecks records,
                           Map<String, Object> caseRecord) {
        ObjectNode stored = answer == null || !answer.isObject()
                ? objectMapper.createObjectNode() : ((ObjectNode) answer).deepCopy();

        stored.remove("debug");

        NominateResponse response = bind(stored);

        String outcome = response.getOutcome() == null ? "null" : response.getOutcome();
        if (!USABLE.equals(outcome)) {
            return new Nomination(outcome, response, stored, null, null, caseRecord);
        }

        Map<String, String> systemFacts = systemFacts(row);
        Map<String, String> dayCounts = new LinkedHashMap<>(dayCountValues(limits.rendered()));

        records.citableValues().forEach(dayCounts::putIfAbsent);
        List<String> shown = response.getRecordFieldsShown() == null
                ? List.<String>of() : response.getRecordFieldsShown();
        RecordCheckResult checked = RecordCheck.apply(response,
                recordFields(shown, caseRecord, systemFacts, dayCounts),
                recordSectionFields(caseRecord, systemFacts, dayCounts));
        return new Nomination(outcome, response, stored, checked, null, caseRecord);
    }

    private NominateResponse bind(JsonNode answer) {
        try {
            return objectMapper.treeToValue(answer, NominateResponse.class);
        } catch (JsonProcessingException exception) {
            throw ServiceStateException.appending("the classifier's answer could not be read",
                    exception.getOriginalMessage(), exception);
        }
    }

    private static ScenarioDocument document(CaseDocument document, SecondRun secondRun) {
        boolean fresh = secondRun != null && secondRun.getNewDocuments().contains(document.getRef());
        return new ScenarioDocument(document.getRef(), document.getDeclaredSide(), fresh ? Boolean.TRUE : null);
    }

    /** What the last run asked the merchant for, as the call shows it. */
    static List<ScenarioPreviousAsk> previousAsks(SecondRun secondRun) {
        List<ScenarioPreviousAsk> out = new ArrayList<>();
        for (MerchantAskRecord ask : secondRun.getAsks()) {
            out.add(new ScenarioPreviousAsk(ask.getAskId(), ask.getDocumentType(), ask.getLabel(), ask.getDetail()));
        }
        return out;
    }

    /** The case block of the call: the card product in words, and the credits and reversals the request carried. */
    @Override
    public Map<String, Object> caseRecord(CaseRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "amount", row.getTransactionAmount() == null ? null : row.getTransactionAmount().toPlainString());
        put(out, "currency", row.getPurchaseCurrency());
        put(out, "date", row.getTransactionDate());
        put(out, "arn", row.getArn());
        put(out, "authCode", row.getAuthorisationCode());
        put(out, "merchantDescriptor", row.getMerchantDescriptor());
        put(out, "mcc", row.getMcc());
        put(out, "cardProduct", tokens.cardProduct(row.getCardProductType()));
        if (row.getCredits() != null) {
            out.put("credits", creditMaps(row.getCredits()));
        }
        if (row.getReversals() != null) {
            out.put("reversals", creditMaps(row.getReversals()));
        }
        return out;
    }

    static List<Map<String, Object>> creditMaps(List<CaseCredit> credits) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CaseCredit credit : credits) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("arn", credit.getArn());
            one.put("date", credit.getDate() == null ? null : credit.getDate().toString());
            one.put("amount", credit.getAmount());
            one.put("currency", credit.getCurrency());
            out.add(one);
        }
        return List.copyOf(out);
    }

    /** The case record's own values the prompt carries as system facts, each only where it has a value. */
    static Map<String, String> caseFacts(CaseRow row, CaseRecordTokens tokens) {
        Map<String, String> facts = new LinkedHashMap<>();
        add(facts, "transaction.mcc", row.getMcc());
        add(facts, "transaction.saleDate", row.getTransactionDate());
        add(facts, "transaction.processDate", row.getProcessingDate());
        add(facts, "dispute.reportedDate", row.getDisputeReportedDate());
        add(facts, "transaction.amount", row.getTransactionAmount());
        add(facts, "transaction.disputedAmount", row.getDisputedAmount());
        add(facts, "dispute.currency", row.getDisputeCurrency());
        add(facts, "transaction.currency", row.getPurchaseCurrency());
        add(facts, "transaction.cashBackAmount", row.getCashBackAmount());
        add(facts, "transaction.merchantCountry", row.getMerchantCountry());
        add(facts, "transaction.channel", tokens.channel(row.getMotoIndicator()));
        add(facts, "transaction.entryMode", tokens.entryMode(row.getEntryMode()));
        return facts;
    }

    private static void add(Map<String, String> facts, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            facts.put(key, String.valueOf(value));
        }
    }

    protected Map<String, String> systemFacts(CaseRow row) {
        Map<String, String> out = new LinkedHashMap<>();
        out.putAll(caseFacts(row, tokens));
        String channel = tokens.channel(row.getMotoIndicator());
        String entryMode = tokens.entryMode(row.getEntryMode());
        String environment = RecordMapping.posEnvironment(channel, entryMode);
        put(out, "transaction.posEnvironment", environment);
        Boolean present = RecordMapping.isCardPresent(environment);
        put(out, "transaction.isCardPresent", present);
        return out;
    }

    List<ScenarioFact> facts(CaseRow row, IssuerFacts issuer) {
        List<ScenarioFact> out = new ArrayList<>();
        systemFacts(row).forEach((key, value) ->
                out.add(new ScenarioFact(key, value, "KNOWN", "SYSTEM", null)));
        if (issuer != null) {
            for (IssuerFact fact : issuer.getFacts()) {
                out.add(new ScenarioFact(fact.getKey(), fact.getValue(), fact.getStatus(),
                        "DOCUMENT", "ISSUER", issuer.label(fact.getKey())));
            }
        }
        return List.copyOf(out);
    }

    static Map<String, String> dayCountValues(List<TimeLimitRow> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        for (TimeLimitRow row : rows == null ? List.<TimeLimitRow>of() : rows) {
            if (row.getDayCount() != null) {
                out.putIfAbsent(row.getDayCountField(), String.valueOf(row.getDayCount()));
            }
        }
        return out;
    }

    private static final Map<String, String> CASE_FIELD_REGISTER_NAMES = Map.of(
            "amount", "transaction.amount", "currency", "transaction.currency",
            "date", "transaction.saleDate", "mcc", "transaction.mcc",
            "cardProduct", "transaction.cardProduct");

    static Map<String, String> recordFields(List<String> shown, Map<String, Object> caseRecord,
                                            Map<String, String> systemFacts,
                                            Map<String, String> dayCounts) {
        Map<String, String> values = new LinkedHashMap<>();
        caseRecord.forEach((field, value) -> {
            if (value instanceof String) {
                values.put(CASE_FIELD_REGISTER_NAMES.getOrDefault(field, field), (String) value);
            }
        });
        systemFacts.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                values.put(key, value);
            }
        });
        values.putAll(dayCounts);
        Map<String, String> out = new LinkedHashMap<>();
        for (String field : shown) {
            String value = values.get(field);
            String currency = "transaction.amount".equals(field) ? values.get("transaction.currency")
                    : "transaction.disputedAmount".equals(field) ? values.get("dispute.currency") : null;
            out.put(field, value != null && currency != null ? value + " " + currency : value);
        }
        return out;
    }

    static java.util.Set<String> recordSectionFields(Map<String, Object> caseRecord,
                                                     Map<String, String> systemFacts,
                                                     Map<String, String> dayCounts) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        caseRecord.forEach((field, value) -> {
            if (value instanceof String) {
                out.add(CASE_FIELD_REGISTER_NAMES.getOrDefault(field, field));
            }
        });
        systemFacts.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                out.add(key);
            }
        });
        out.addAll(dayCounts.keySet());
        return out;
    }

    private static void put(Map<String, ? super String> out, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.put(name, String.valueOf(value));
        }
    }
}
