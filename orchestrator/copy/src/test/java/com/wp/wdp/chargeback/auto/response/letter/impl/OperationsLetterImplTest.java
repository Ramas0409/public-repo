package com.wp.wdp.chargeback.auto.response.letter.impl;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.util.LogCapture;
import com.wp.wdp.chargeback.auto.response.letter.ExhibitPages;
import com.wp.wdp.chargeback.auto.response.letter.OperationsLetter;
import com.wp.wdp.chargeback.auto.response.letter.Summaries;
import com.wp.wdp.chargeback.auto.response.letter.model.SummaryResult;
import com.wp.wdp.chargeback.auto.response.model.decision.UndecidedKind;
import com.wp.wdp.chargeback.auto.response.service.Cases;

class OperationsLetterImplTest {
    private final OperationsLetter letters = new OperationsLetterImpl(Cases.JSON, Summaries.SIGNATURE);

    @Test
    @DisplayName("the summary comes back as a base64 PDF, and says what the run decided and when")
    void the_summary_is_a_pdf() {
        SummaryResult summary = letters.generate(Cases.bsh(), null, null, null, null, null, List.of(),
                UndecidedKind.RUN_FAILED, Summaries.CREATED_AT, Summaries.RESPOND_BY, true, null);
        byte[] pdf = Base64.getDecoder().decode(summary.getPdf());

        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(ExhibitPages.textOf(pdf, 1)).contains("Undecided", "The run did not finish", "£25.46",
                "21 Aug 2025", "16 days left", "Re-run the case", "Case POCVISA13117 · 5 Aug 2025, 14:32 UTC",
                "Page 1 of 1");
    }

    @Test
    @DisplayName("a summary that cannot be built is null, never a failed run")
    void a_summary_that_cannot_be_built_is_null() {
        assertThat(letters.generate(null, null, null, null, null, null, List.of(), null, Summaries.CREATED_AT, null, true, null)
                .getPdf()).isNull();
    }

    @Test
    @DisplayName("a summary that cannot be built is one WARN, event=opsletter.failed, with the log-safe trace")
    void a_summary_that_cannot_be_built_logs_its_trace() {
        try (LogCapture logs = LogCapture.open()) {
            letters.generate(null, null, null, null, null, null, List.of(), null, Summaries.CREATED_AT, null, true, null);
            assertThat(logs.withEvent("opsletter.failed")).singleElement().satisfies(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.WARN);
                assertThat(line.getThrowableProxy()).isNotNull();
                assertThat(line.getFormattedMessage()).isEqualTo("event=opsletter.failed step=content error=NullPointerException");
            });
        }
    }
}
