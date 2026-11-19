package com.wp.wdp.chargeback.auto.response.letter.impl;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.wp.wdp.chargeback.auto.response.letter.MerchantMessageBuilder;
import com.wp.wdp.chargeback.auto.response.letter.OperationsLetter;
import com.wp.wdp.chargeback.auto.response.letter.OpsLetterContentBuilder;
import com.wp.wdp.chargeback.auto.response.letter.OpsLetterRenderer;
import com.wp.wdp.chargeback.auto.response.letter.dto.LetterFinding;
import com.wp.wdp.chargeback.auto.response.letter.model.OpsLetterContent;
import com.wp.wdp.chargeback.auto.response.letter.model.SummaryResult;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.check.RecordChecks;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimits;
import com.wp.wdp.chargeback.auto.response.model.decision.Decision;
import com.wp.wdp.chargeback.auto.response.model.decision.Nomination;
import com.wp.wdp.chargeback.auto.response.model.decision.SecondRun;
import com.wp.wdp.chargeback.auto.response.model.decision.UndecidedKind;
import com.wp.wdp.chargeback.auto.response.service.register.ArgumentCatalogue;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;

@Component
public class OperationsLetterImpl implements OperationsLetter {
    private static final Logger log = LoggerFactory.getLogger(OperationsLetter.class);

    private final JsonNode wording;
    private final String signature;

    public OperationsLetterImpl(ObjectMapper objectMapper,
                                @Value("${auto-contest.merchant-message.signature}") String signature) {
        this.wording = MerchantMessageBuilder.wording(objectMapper);
        this.signature = signature;
    }

    @Override
    public SummaryResult generate(CaseRow row, Decision decision, ArgumentCatalogue catalogue, Nomination nomination,
                                  TimeLimits limits, RecordChecks records, List<LetterFinding> findings,
                                  UndecidedKind kind, OffsetDateTime createdAt, String respondBy,
                                  boolean networkResponseBuilt, SecondRun secondRun) {
        OpsLetterContent content;
        try {
            content = OpsLetterContentBuilder.build(row, decision, catalogue, nomination, limits, records, findings,
                    kind, createdAt, respondBy, networkResponseBuilt, secondRun, wording, signature);
        } catch (RuntimeException exception) {
            log.warn("event=opsletter.failed step=content error={}", exception.getClass().getSimpleName(),
                    LogSafe.of(exception));
            return new SummaryResult(null, null);
        }
        try {
            return new SummaryResult(Base64.getEncoder().encodeToString(OpsLetterRenderer.render(content)), content);
        } catch (RuntimeException exception) {
            log.warn("event=opsletter.failed step=render error={}", exception.getClass().getSimpleName(),
                    LogSafe.of(exception));
            return new SummaryResult(null, content);
        }
    }
}
