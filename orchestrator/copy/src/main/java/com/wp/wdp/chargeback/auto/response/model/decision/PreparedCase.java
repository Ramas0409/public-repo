package com.wp.wdp.chargeback.auto.response.model.decision;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import com.wp.wdp.chargeback.auto.response.model.casefile.CaseRow;
import com.wp.wdp.chargeback.auto.response.model.casefile.IssuerFacts;
import com.wp.wdp.chargeback.auto.response.model.check.RecordChecks;
import com.wp.wdp.chargeback.auto.response.model.check.TimeLimits;
import com.wp.wdp.chargeback.auto.response.model.register.RegisterRow;
import com.wp.wdp.chargeback.auto.response.service.register.ArgumentCatalogue;

/** A case ready for the classifier call: its register, the issuer's answers, the checks and the rendered list. */
@Getter
@RequiredArgsConstructor
public final class PreparedCase {
    private final CaseRow row;
    private final Long reasonCodeId;
    private final String region;
    private final ArgumentCatalogue catalogue;
    private final Map<String, RegisterRow> rows;
    private final IssuerFacts issuer;
    private final TimeLimits limits;
    private final RecordChecks records;
    private final RenderedPayload payload;
    private final List<String> problems;
    private final String error;
    @Setter
    private SecondRun secondRun;
    @Setter
    private long factsMillis;

    /** A case whose network and reason code have no register row. */
    public static PreparedCase notJudgeable(CaseRow row, String error) {
        return new PreparedCase(row, null, null, null, Map.of(), null, null, null, null, List.of(error), error);
    }

    public boolean isJudgeable() {
        return error == null;
    }
}
