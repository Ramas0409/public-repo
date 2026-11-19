package com.wp.wdp.chargeback.auto.response.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.wp.wdp.chargeback.auto.response.dto.llm.ExtractResponse;
import com.wp.wdp.chargeback.auto.response.dto.llm.ExtractedFact;
import com.wp.wdp.chargeback.auto.response.dto.llm.RequiredKey;
import com.wp.wdp.chargeback.auto.response.dto.llm.SuppliedDocumentRef;
import com.wp.wdp.chargeback.auto.response.dto.llm.TransactionContext;
import com.wp.wdp.chargeback.auto.response.entity.FactKeyEntity;
import com.wp.wdp.chargeback.auto.response.exception.ModelCallRefused;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseDocument;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFact;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFacts;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterKey;
import com.wp.wdp.chargeback.auto.response.repository.FactKeyRepository;
import com.wp.wdp.chargeback.auto.response.service.IssuerFactsReader;
import com.wp.wdp.chargeback.auto.response.service.LlmClient;
import com.wp.wdp.chargeback.auto.response.exception.ServiceStateException;

@RequiredArgsConstructor
@Component
public class IssuerFactsReaderImpl implements IssuerFactsReader {
    private static final Logger log = LoggerFactory.getLogger(IssuerFactsReader.class);

    private final FactKeyRepository factKeys;
    private final LlmClient llmClient;

    @Override
    public Map<String, RegisterKey> keys(Long reasonCodeId, List<String> regions) {
        Map<String, RegisterKey> out = new LinkedHashMap<>();
        for (FactKeyEntity key : factKeys.findDocumentKeys(reasonCodeId, regions)) {
            out.put(key.getFactKey(), new RegisterKey(key.getId(), key.getFactKey(), key.getQuestion(), key.getType(),
                    key.getValueDomain(), key.getSourceRef(), key.getOrigin(), key.getContractVersion(), key.getLabel()));
        }
        return out;
    }

    @Override
    public IssuerFacts read(CaseRow row, Long reasonCodeId, List<String> regions) {
        return read(row, reasonCodeId, regions, false);
    }

    @Override
    public IssuerFacts read(CaseRow row, Long reasonCodeId, List<String> regions, boolean replayOnly) {
        Map<String, RegisterKey> keys = keys(reasonCodeId, regions);

        List<IssuerFact> facts = new ArrayList<>();
        List<String> notAnswered = new ArrayList<>();
        int replayed = 0;
        int extracted = 0;

        for (CaseDocument document : row.issuerDocuments()) {
            List<RequiredKey> toAsk = new ArrayList<>();
            for (RegisterKey key : keys.values()) {
                toAsk.add(required(key, document));
            }
            if (toAsk.isEmpty()) {
                continue;
            }
            log.debug("event=facts.ask doc={} keys={} replayOnly={}", row.documentName(document.getRef()), toAsk.size(),
                    replayOnly);
            ExtractResponse response = llmClient.extract(row.getCaseNumber(), row.getActionSequence(),
                    row.getAcquiringPlatform(), toAsk, anchor(row), replayOnly);
            List<ExtractedFact> answers = response == null || response.getFacts() == null
                    ? List.<ExtractedFact>of() : response.getFacts();

            List<String> refusedKeys = new ArrayList<>();
            for (ExtractedFact answer : answers) {
                if (FROM_REFUSED.equals(answer.getAnsweredFrom()) && !refusedKeys.contains(answer.getKey())) {
                    refusedKeys.add(answer.getKey());
                }
            }
            if (!refusedKeys.isEmpty()) {
                throw new ModelCallRefused(document.getRef(), row.documentName(document.getRef()), refusedKeys);
            }

            for (RegisterKey key : keys.values()) {
                List<ExtractedFact> rows = new ArrayList<>();
                for (ExtractedFact answer : answers) {
                    if (key.getKey().equals(answer.getKey())
                            && (answer.getDocumentRef() == null || answer.getDocumentRef().equals(document.getRef()))) {
                        rows.add(answer);
                    }
                }
                IssuerFact resolved = resolve(key.getKey(), rows);
                if (resolved == null) {
                    continue;
                }
                facts.add(resolved);
                if (REPLAYED.equals(resolved.getOrigin())) {
                    replayed++;
                } else {
                    extracted++;
                }
            }
        }

        for (RegisterKey key : keys.values()) {
            if (facts.stream().noneMatch(fact -> fact.getKey().equals(key.getKey()) && fact.isAnswered())) {
                notAnswered.add(key.getKey());
            }
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (RegisterKey key : keys.values()) {
            if (key.getLabel() != null) {
                labels.put(key.getKey(), key.getLabel());
            }
        }
        return new IssuerFacts(List.copyOf(facts), row.getNetworkCaseNumber(), row.getIssuerName(),
                replayed, extracted, List.copyOf(notAnswered), labels);
    }

    /** A settled answer first, else the producer's UNRESOLVED, else a NOT_FOUND - as the sheet resolves it. */
    static IssuerFact resolve(String key, List<ExtractedFact> rows) {
        ExtractedFact known = rows.stream().filter(IssuerFactsReaderImpl::isSettled).findFirst().orElse(null);
        if (known != null) {
            return new IssuerFact(key, known.getStatus(), text(known.getValue()), known.getPage(),
                    known.getDocumentRef(), known.getQuote(), origin(known));
        }
        ExtractedFact unresolved = rows.stream().filter(fact -> "UNRESOLVED".equals(fact.getStatus()))
                .findFirst().orElse(null);
        if (unresolved != null) {
            return new IssuerFact(key, "UNRESOLVED", null, unresolved.getPage(), unresolved.getDocumentRef(),
                    unresolved.getQuote(), origin(unresolved));
        }
        ExtractedFact notFound = rows.stream().filter(fact -> "NOT_FOUND".equals(fact.getStatus()))
                .findFirst().orElse(null);
        return notFound == null ? null
                : new IssuerFact(key, "NOT_FOUND", null, null, notFound.getDocumentRef(), null, origin(notFound));
    }

    private static boolean isSettled(ExtractedFact fact) {
        return "KNOWN".equals(fact.getStatus()) || "JUDGED".equals(fact.getStatus());
    }

    private static String origin(ExtractedFact fact) {
        return FROM_STORE.equals(fact.getAnsweredFrom()) ? REPLAYED : EXTRACTED;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static RequiredKey required(RegisterKey key, CaseDocument document) {
        RequiredKey required = new RequiredKey(
                key.getKey(), key.getQuestion(), key.getType() == null ? "value" : key.getType(),
                key.getValueDomain(),
                List.of(new SuppliedDocumentRef(document.getRef(), key.getSourceRef())),
                "MODEL".equals(key.getOrigin()) ? "JUDGE" : "EXTRACT",
                key.getContractVersion(), questionVersion(key.getQuestion()), key.getId());
        assertDomain(key, required);
        return required;
    }

    private static void assertDomain(RegisterKey key, RequiredKey required) {
        String declared = key.getValueDomain();
        if (declared == null || declared.isBlank()) {
            return;
        }
        if (!declared.equals(required.getDomain())) {
            throw new ServiceStateException(
                    "the register declares " + key.getKey() + " as " + declared
                            + " and the request would have carried " + required.getDomain()
                            + " - the answer would come back unnormalised");
        }
    }

    static TransactionContext anchor(CaseRow row) {
        return new TransactionContext(
                row.getTransactionAmount() == null ? null : row.getTransactionAmount().toPlainString(),
                row.getPurchaseCurrency(),
                row.getTransactionDate() == null ? null : row.getTransactionDate().toString(),
                row.getArn(), row.getAuthorisationCode(), row.getMerchantDescriptor(), row.getMcc());
    }

    static String questionVersion(String question) {
        return "q" + Integer.toHexString((question == null ? "" : question).hashCode());
    }
}
