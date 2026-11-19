package com.wp.wdp.chargeback.auto.response.exception;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.wp.wdp.chargeback.auto.response.controller.AutoContestController;
import com.wp.wdp.chargeback.auto.response.model.decision.ContestResult;
import com.wp.wdp.chargeback.auto.response.service.Cases;
import com.wp.wdp.chargeback.auto.response.service.ClassifierContest;
import com.wp.wdp.chargeback.auto.response.service.StubRegisterRows;
import com.wp.wdp.chargeback.auto.response.service.impl.EnabledReasonCodesImpl;
import com.wp.wdp.chargeback.auto.response.util.LogCapture;

class GlobalExceptionHandlerTest {
    private static final String SECRET = "Bea Example 14 Sample Row";

    private static MockMvc http(ClassifierContest contest) {
        return MockMvcBuilders.standaloneSetup(new AutoContestController(contest,
                new EnabledReasonCodesImpl(new StubRegisterRows()))).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static List<ILoggingEvent> warnings(LogCapture logs) {
        return logs.service().stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
    }

    @Test
    @DisplayName("a 400 is one WARN with the field names and the count, no stack and no rejected value; the body is unchanged")
    void a_400_is_one_warn_with_field_names() throws Exception {
        ObjectNode body = Cases.requestNode(Cases.bsh()).deepCopy();
        ((ObjectNode) body.get("dispute")).put("currency", SECRET);
        ((ObjectNode) body.get("documents").get(0)).put("side", SECRET);
        try (LogCapture logs = LogCapture.open()) {
            http(null).perform(post("/auto-contest").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.target == 'dispute.currency:" + SECRET + "')]").exists());
            List<ILoggingEvent> lines = warnings(logs);
            assertThat(lines).hasSize(1);
            ILoggingEvent line = lines.get(0);
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage())
                    .isEqualTo("event=request.refused status=400 errors=2 fields=dispute.currency,documents[0].side");
            assertThat(line.getThrowableProxy()).isNull();
            assertThat(logs.printedAll()).doesNotContain(SECRET);
        }
    }

    @Test
    @DisplayName("a reason code the register does not enable is one WARN naming the field, not its value")
    void a_refusal_names_its_field() throws Exception {
        ObjectNode body = Cases.requestNode(Cases.bsh()).deepCopy();
        body.put("reasonCode", "99.9");
        try (LogCapture logs = LogCapture.open()) {
            http(null).perform(post("/auto-contest").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                    .andExpect(status().isBadRequest());
            assertThat(warnings(logs)).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("event=request.refused status=400 errors=1 fields=reasonCode");
        }
    }

    @Test
    @DisplayName("a 500 is one ERROR with the full log-safe trace, the raising frame included and the message withheld")
    void a_500_is_one_error_with_the_trace() throws Exception {
        try (LogCapture logs = LogCapture.open()) {
            http(request -> raiseFromInsideThePipeline()).perform(post("/auto-contest")
                            .contentType(MediaType.APPLICATION_JSON).content(Cases.requestNode(Cases.bsh()).toString()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errors[0].target").value("System Error"));
            List<ILoggingEvent> lines = warnings(logs);
            assertThat(lines).hasSize(1);
            ILoggingEvent line = lines.get(0);
            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            assertThat(line.getFormattedMessage())
                    .isEqualTo("event=request.failed status=500 reason=unexpected error=IllegalArgumentException");
            String printed = logs.printed(line);
            assertThat(printed).contains("java.lang.IllegalArgumentException: (message withheld)")
                    .contains("raiseFromInsideThePipeline").doesNotContain(SECRET);
        }
    }

    private static ContestResult raiseFromInsideThePipeline() {
        throw new IllegalArgumentException("a third party read " + SECRET);
    }

    @Test
    @DisplayName("a replay-only refusal's 409 body keeps the key it carries today; its line names the field alone")
    void a_replay_only_refusal_keeps_its_body() {
        ModelCallRefused refused = new ModelCallRefused("C/issuer/Bea Example form.pdf", "issuer#1",
                List.of("dispute.claimType"));
        try (LogCapture logs = LogCapture.open()) {
            StandardErrorResponse body = new GlobalExceptionHandler().modelCallRefused(refused).getBody();
            assertThat(body.getErrors()).singleElement().satisfies(error -> {
                assertThat(error.getErrorMessage()).isEqualTo("replay-only: 1 key(s) of C/issuer/Bea Example form.pdf"
                        + " would have been asked of the model: dispute.claimType");
                assertThat(error.getTarget()).isEqualTo("C/issuer/Bea Example form.pdf");
            });
            assertThat(refused.getMessage()).isEqualTo(
                    "replay-only: 1 key(s) of doc=issuer#1 would have been asked of the model: dispute.claimType");
            assertThat(warnings(logs)).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("event=request.refused status=409 errors=1 fields=documents");
            assertThat(logs.printedAll()).doesNotContain("Bea Example");
        }
    }

    @Test
    @DisplayName("a body that cannot be read is the caller's: one WARN refusal with the JSON location, no trace and no text")
    void an_unreadable_body_is_one_warn_with_its_location() throws Exception {
        try (LogCapture logs = LogCapture.open()) {
            http(null).perform(post("/auto-contest").contentType(MediaType.APPLICATION_JSON)
                            .content("{\n  \"caseNumber\": \"" + SECRET + "\",\n  oops }"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.errors[0].target").value("Message Converter"));
            http(null).perform(post("/auto-contest").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError());
            List<ILoggingEvent> lines = warnings(logs);
            assertThat(lines).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                    "event=request.refused status=500 reason=unreadable-body location=3:3",
                    "event=request.refused status=500 reason=unreadable-body location=-");
            assertThat(lines).allSatisfy(line -> {
                assertThat(line.getLevel()).isEqualTo(Level.WARN);
                assertThat(line.getThrowableProxy()).isNull();
            });
            assertThat(logs.printedAll()).doesNotContain(SECRET);
        }
    }

    private static List<String> sortedCopy(List<String> keys) {
        List<String> sorted = new java.util.ArrayList<>(keys);
        sorted.sort(null);
        return sorted;
    }

    @Test
    @DisplayName("a 400 lists its errors sorted by field, then message, whatever order the validator gives them")
    void a_400_lists_its_errors_in_field_order() throws Exception {
        ObjectNode body = Cases.requestNode(Cases.bsh()).deepCopy();
        body.remove("caseNumber");
        ((ObjectNode) body.get("dispute")).put("currency", "gbp");
        ((ObjectNode) body.get("documents").get(0)).put("side", "BANK");
        ((ObjectNode) body.get("transaction")).put("date", "03/06/2025");
        body.put("platform", "EMEA");
        String answer = http(null).perform(post("/auto-contest").contentType(MediaType.APPLICATION_JSON)
                .content(body.toString())).andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString();
        List<String> keys = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode error : Cases.JSON.readTree(answer).path("errors")) {
            String target = error.path("target").asText();
            keys.add(target.substring(0, target.indexOf(':')) + "\u0000" + error.path("errorMessage").asText());
        }
        assertThat(keys).hasSize(5).isEqualTo(sortedCopy(keys));
        assertThat(answer).startsWith("{\"errors\":[{\"errorMessage\":\"is required\",\"target\":\"caseNumber:null\"}");
    }

    @Test
    @DisplayName("a constraint violation's 400 lists its errors sorted by field, then message")
    void a_constraint_400_lists_its_errors_in_field_order() {
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory()
                .getValidator();
        java.util.Set<jakarta.validation.ConstraintViolation<com.wp.wdp.chargeback.auto.response.dto.autocontest
                .AutoContestRequest>> violations = validator.validate(
                new com.wp.wdp.chargeback.auto.response.dto.autocontest.AutoContestRequest());
        assertThat(violations).hasSizeGreaterThan(3);
        StandardErrorResponse answer = new GlobalExceptionHandler()
                .constraintViolationException(new ConstraintViolationException(violations)).getBody();
        List<String> keys = new java.util.ArrayList<>();
        for (StandardDisplayError error : answer.getErrors()) {
            keys.add(error.getTarget().substring(0, error.getTarget().indexOf(':')) + "\u0000"
                    + error.getErrorMessage());
        }
        assertThat(keys).isEqualTo(sortedCopy(keys));
    }
}

