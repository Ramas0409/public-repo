package com.wp.wdp.chargeback.auto.response.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class LogContextTest {

    @AfterEach
    void clear() {
        LogContext.clear();
    }

    @Test
    @DisplayName("the case key goes into the logging context under the Java names, with the key the line prints")
    void the_case_key_is_in_the_context() {
        LogContext.putCase("NAP", "POCVISA13S01", "01");
        LogContext.putRunId(4242L);

        assertThat(MDC.get("platform")).isEqualTo("NAP");
        assertThat(MDC.get("caseNumber")).isEqualTo("POCVISA13S01");
        assertThat(MDC.get("actionSequence")).isEqualTo("01");
        assertThat(MDC.get("caseKey")).isEqualTo("NAP/POCVISA13S01/01");
        assertThat(MDC.get("runId")).isEqualTo("4242");
    }

    @Test
    @DisplayName("a part the request does not carry is a dash in the key; clear removes every key")
    void a_missing_part_is_a_dash_and_clear_removes_all() {
        LogContext.putCase("CORE", "POCVISA13S01", null);
        assertThat(MDC.get("caseKey")).isEqualTo("CORE/POCVISA13S01/-");

        LogContext.clear();
        for (String key : new String[] {"platform", "caseNumber", "actionSequence", "caseKey", "runId"}) {
            assertThat(MDC.get(key)).isNull();
        }
    }
}
