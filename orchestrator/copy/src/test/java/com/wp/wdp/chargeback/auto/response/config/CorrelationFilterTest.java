package com.wp.wdp.chargeback.auto.response.config;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.util.LogCapture;
import com.wp.wdp.chargeback.auto.response.util.LogContext;
import com.wp.wdp.chargeback.auto.response.util.RequestCorrelation;
import org.slf4j.MDC;

class CorrelationFilterTest {

    @Test
    @DisplayName("the caller's id is current through the request, echoed on the response, and gone after it")
    void the_callers_id_is_carried() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auto-contest");
        request.addHeader("v-correlation-id", "corr-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        List<String> seen = new ArrayList<>();

        new CorrelationFilter().doFilter(request, response, (in, out) -> seen.add(RequestCorrelation.getId()));

        assertThat(seen).containsExactly("corr-123");
        assertThat(response.getHeader("v-correlation-id")).isEqualTo("corr-123");
        assertThat(RequestCorrelation.getId()).isNull();
    }

    @Test
    @DisplayName("a request without an id gets a new one")
    void a_request_without_an_id_gets_one() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationFilter().doFilter(new MockHttpServletRequest("GET", "/health"), response, (in, out) -> { });

        assertThat(response.getHeader("v-correlation-id")).hasSize(36);
    }

    @Test
    @DisplayName("A10: a caller's id is kept only if it passes the check, else replaced and logged by its length alone")
    void an_invalid_id_is_replaced_and_logged_by_its_length() throws Exception {
        for (String kept : new String[] {"abc-123:x.y_Z", "a".repeat(64), "7c1e2f30-aaaa-4bbb-8ccc-0123456789ab"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auto-contest");
            request.addHeader("v-correlation-id", kept);
            MockHttpServletResponse response = new MockHttpServletResponse();
            new CorrelationFilter().doFilter(request, response, (in, out) -> { });
            assertThat(response.getHeader("v-correlation-id")).isEqualTo(kept);
        }
        for (String refused : new String[] {"a".repeat(65), "has a space", "Bea Example"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auto-contest");
            request.addHeader("v-correlation-id", refused);
            MockHttpServletResponse response = new MockHttpServletResponse();
            try (LogCapture logs = LogCapture.open()) {
                new CorrelationFilter().doFilter(request, response, (in, out) -> { });
                String made = response.getHeader("v-correlation-id");
                assertThat(made).hasSize(36).isNotEqualTo(refused);
                assertThat(logs.withEvent("corr.replaced")).singleElement().satisfies(line -> {
                    assertThat(line.getLevel()).isEqualTo(Level.WARN);
                    assertThat(line.getFormattedMessage()).isEqualTo("event=corr.replaced len=" + refused.length());
                    assertThat(line.getMDCPropertyMap()).containsEntry("correlationId", made);
                });
                assertThat(logs.printedAll()).doesNotContain(refused);
            }
        }
    }

    @Test
    @DisplayName("a missing id is made silently; every context key the request put is gone after it")
    void a_missing_id_is_silent_and_the_context_is_cleared() throws Exception {
        try (LogCapture logs = LogCapture.open()) {
            new CorrelationFilter().doFilter(new MockHttpServletRequest("POST", "/auto-contest"),
                    new MockHttpServletResponse(), (in, out) -> {
                        LogContext.putCase("NAP", "POCVISA13S01", "01");
                        LogContext.putRunId(7L);
                    });
            assertThat(logs.withEvent("corr.replaced")).isEmpty();
        }
        for (String key : new String[] {"correlationId", "platform", "caseNumber", "actionSequence", "caseKey", "runId"}) {
            assertThat(MDC.get(key)).isNull();
        }
    }
}
