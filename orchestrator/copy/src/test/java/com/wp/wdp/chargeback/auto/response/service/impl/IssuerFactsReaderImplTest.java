package com.wp.wdp.chargeback.auto.response.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.wp.wdp.chargeback.auto.response.dto.llm.ExtractResponse;
import com.wp.wdp.chargeback.auto.response.dto.llm.ExtractedFact;
import com.wp.wdp.chargeback.auto.response.dto.llm.RequiredKey;
import com.wp.wdp.chargeback.auto.response.dto.llm.TransactionContext;
import com.wp.wdp.chargeback.auto.response.exception.ConflictException;
import com.wp.wdp.chargeback.auto.response.exception.ModelCallRefused;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseDocument;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFacts;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterKey;
import com.wp.wdp.chargeback.auto.response.service.Cases;
import com.wp.wdp.chargeback.auto.response.service.IssuerFactsReader;
import com.wp.wdp.chargeback.auto.response.service.StubIssuerFactsReader;
import com.wp.wdp.chargeback.auto.response.service.StubLlm;
import com.wp.wdp.chargeback.auto.response.util.LogCapture;

class IssuerFactsReaderImplTest {
    private static final String FORM = "POCVISA13117/issuer/issuer.pdf";
    private static final String EXPECTED = "cardholder.expectedReceiptDate";
    private static final String INSOLVENT = "issuer.certifiesMerchantInsolvent";
    private static final String CONTRACT = "c3db60f279a";

    private static Map<String, RegisterKey> twoKeys() {
        Map<String, RegisterKey> out = new LinkedHashMap<>();
        out.put(EXPECTED, new RegisterKey(11L, EXPECTED, "When did the cardholder expect it?",
                "value", "ISO_DATE", IssuerFactsReader.ISSUER_FORM, "DOCUMENT", CONTRACT));
        out.put(INSOLVENT, new RegisterKey(12L, INSOLVENT, "Is the merchant insolvent?",
                "truth", null, IssuerFactsReader.ISSUER_FORM, "DOCUMENT", "c5cd561bc1b"));
        return out;
    }

    private static ExtractedFact answered(String key, String status, Object value, String from) {
        return new ExtractedFact(key, status, value, "a span", 1, FORM, IssuerFactsReader.ISSUER_FORM,
                "DIGITAL_TEXT", "gpt", null, from);
    }

    private static IssuerFactsReader reader(Map<String, RegisterKey> keys, StubLlm llm) {
        return new StubIssuerFactsReader(llm, keys);
    }

    @Test
    @DisplayName("CL-131: every key of every issuer document is asked in one call, with its versions and its row id")
    void every_key_is_asked_once_with_its_versions() {
        StubLlm llm = new StubLlm();
        llm.extractResponse = new ExtractResponse("POCVISA13117", List.of(
                answered(EXPECTED, "KNOWN", "2025-06-20", IssuerFactsReader.FROM_STORE),
                answered(INSOLVENT, "KNOWN", false, "MODEL")), List.of(), "gpt", "2.1.0");

        IssuerFacts result = reader(twoKeys(), llm).read(Cases.bsh(), Cases.REASON_CODE_ID, List.of("ALL"), false);

        assertThat(llm.extractCalls).hasSize(1);
        assertThat(llm.extractCalls.get(0)).extracting(RequiredKey::getKey).containsExactly(EXPECTED, INSOLVENT);
        RequiredKey expected = llm.extractCalls.get(0).get(0);
        assertThat(expected.getContractVersion()).isEqualTo(CONTRACT);
        assertThat(expected.getQuestionVersion()).isEqualTo(IssuerFactsReaderImpl.questionVersion("When did the cardholder expect it?"));
        assertThat(expected.getFactKeyId()).isEqualTo(11L);
        assertThat(llm.extractRequests.get(0).getCaseNumber()).isEqualTo("POCVISA13117");
        assertThat(llm.extractRequests.get(0).getActionSequence()).isEqualTo("01");
        assertThat(llm.extractRequests.get(0).getPlatform()).isEqualTo("NAP");
        assertThat(llm.extractRequests.get(0).isReplayOnly()).isFalse();

        assertThat(result.getReplayed()).describedAs("counted off answeredFrom").isEqualTo(1);
        assertThat(result.getExtracted()).isEqualTo(1);
        assertThat(result.fact(EXPECTED).getOrigin()).isEqualTo(IssuerFactsReader.REPLAYED);
        assertThat(result.fact(INSOLVENT).getOrigin()).isEqualTo(IssuerFactsReader.EXTRACTED);
        assertThat(result.value(INSOLVENT)).describedAs("a boolean crosses as text").isEqualTo("false");
        assertThat(result.getNotAnswered()).isEmpty();
    }

    @Test
    @DisplayName("replay-only: the flag travels, and a REFUSED key is a ModelCallRefused naming the document and the keys")
    void a_refusal_is_raised_not_read() {
        StubLlm llm = new StubLlm();
        IssuerFactsReader reader = reader(twoKeys(), llm);

        assertThatThrownBy(() -> reader.read(Cases.bsh(), Cases.REASON_CODE_ID, List.of("ALL"), true))
                .isInstanceOf(ModelCallRefused.class)
                .isInstanceOf(com.wp.wdp.chargeback.auto.response.exception.ConflictException.class)
                .hasMessageContaining("2 key(s) of doc=issuer#1")
                .hasMessageNotContaining(FORM)
                .hasMessageContaining(EXPECTED).hasMessageContaining(INSOLVENT);
        assertThat(llm.extractRequests.get(0).isReplayOnly()).isTrue();
    }

    @Test
    @DisplayName("the line asking an issuer document's keys is DEBUG and names the document by its place, never its key")
    void the_asking_line_names_the_document_by_its_place() {
        StubLlm llm = new StubLlm();
        llm.extractResponse = new ExtractResponse("POCVISA13117", List.of(
                answered(EXPECTED, "KNOWN", "2025-06-20", IssuerFactsReader.FROM_STORE)), List.of(), "gpt", "2.1.0");
        try (LogCapture logs = LogCapture.open()) {
            reader(twoKeys(), llm).read(Cases.bsh(), Cases.REASON_CODE_ID, List.of("ALL"), false);
            assertThat(logs.withEvent("facts.ask")).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(line.getFormattedMessage()).isEqualTo("event=facts.ask doc=issuer#1 keys=2 replayOnly=false");
            });
            assertThat(logs.printedAll()).doesNotContain(FORM);
        }
    }

    @Test
    @DisplayName("a NOT_FOUND is a settled answer that answers nothing; a settled row outranks an UNRESOLVED")
    void resolution_follows_the_sheet() {
        StubLlm llm = new StubLlm();
        llm.extractResponse = new ExtractResponse("POCVISA13117", List.of(
                answered(EXPECTED, "UNRESOLVED", null, IssuerFactsReader.FROM_STORE),
                answered(EXPECTED, "KNOWN", "2025-06-20", IssuerFactsReader.FROM_STORE),
                new ExtractedFact(INSOLVENT, "NOT_FOUND", null, null, null, FORM, IssuerFactsReader.ISSUER_FORM,
                        "DIGITAL_TEXT", "gpt", null, IssuerFactsReader.FROM_STORE)),
                List.of(), "gpt", "2.1.0");

        IssuerFacts result = reader(twoKeys(), llm).read(Cases.bsh(), Cases.REASON_CODE_ID, List.of("ALL"), false);

        assertThat(result.value(EXPECTED)).isEqualTo("2025-06-20");
        assertThat(result.fact(INSOLVENT).getStatus()).isEqualTo("NOT_FOUND");
        assertThat(result.value(INSOLVENT)).isNull();
        assertThat(result.getNotAnswered()).containsExactly(INSOLVENT);
        assertThat(result.getReplayed()).isEqualTo(2);
    }

    @Test
    @DisplayName("a case with no issuer document asks nothing and answers nothing")
    void no_issuer_document_no_call() {
        StubLlm llm = new StubLlm();
        IssuerFacts result = reader(twoKeys(), llm).read(Cases.withDocuments(Cases.bsh(), List.of(
                new CaseDocument("POCVISA13117/merchant/merchant.pdf", "MERCHANT"))), Cases.REASON_CODE_ID,
                List.of("ALL"), true);
        assertThat(llm.extractCalls).isEmpty();
        assertThat(result.getNotAnswered()).containsExactly(EXPECTED, INSOLVENT);
    }

    @Test
    @DisplayName("the request is pass 1's, field for field, plus the three CL-131 fields")
    void the_request_matches_pass_one() {
        RegisterKey key = twoKeys().get(EXPECTED);
        CaseDocument document = Cases.bsh().issuerDocuments().get(0);

        RequiredKey built = IssuerFactsReaderImpl.required(key, document);

        assertThat(built.getKey()).isEqualTo(EXPECTED);
        assertThat(built.getQuestion()).isEqualTo(key.getQuestion());
        assertThat(built.getType()).isEqualTo("value");
        assertThat(built.getDomain()).isEqualTo("ISO_DATE");
        assertThat(built.getAnswerMode()).isEqualTo("EXTRACT");
        assertThat(built.getDocuments()).singleElement().satisfies(supplied -> {
            assertThat(supplied.getRef()).isEqualTo(document.getRef());
            assertThat(supplied.getDocumentType()).isEqualTo(IssuerFactsReader.ISSUER_FORM);
        });
        assertThat(built.getContractVersion()).isEqualTo(CONTRACT);
        assertThat(built.getFactKeyId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("the anchor carries the TRANSACTION amount, as pass 1's does")
    void the_anchor_matches_pass_one() {
        TransactionContext anchor = IssuerFactsReaderImpl.anchor(Cases.bsh());
        assertThat(anchor.getAmount()).isEqualTo("25.46");
        assertThat(anchor.getCurrency()).isEqualTo("GBP");
        assertThat(anchor.getDate()).isEqualTo("2025-06-03");
        assertThat(anchor.getArn()).isEqualTo("74838565156920021001348");
        assertThat(anchor.getAuthCode()).isEqualTo("123456");
        assertThat(anchor.getMerchantDescriptor()).isEqualTo("BSH HOME APPS LTD");
        assertThat(anchor.getMcc()).isEqualTo("5722");
    }

    @Test
    @DisplayName("the question version is pass 1's hash, or a stored row would never replay")
    void the_question_version_matches_pass_one() {
        String question = "When did the cardholder expect it?";
        assertThat(IssuerFactsReaderImpl.questionVersion(question))
                .isEqualTo("q" + Integer.toHexString(question.hashCode()));
        assertThat(IssuerFactsReaderImpl.questionVersion(null)).isEqualTo("q0");
    }
}
