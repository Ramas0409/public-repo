package com.wp.wdp.chargeback.auto.response.exception;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.service.Cases;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;

class LlmCallFailedTest {
    @Test
    @DisplayName("each error of the body is named with its target's field, never the target's value")
    void every_error_is_named_with_its_target() {
        String body = "{\"errors\":[{\"errorMessage\":\"LLM gateway error: timed out\",\"target\":\"LLM Gateway\"},"
                + "{\"errorMessage\":\"second\",\"target\":\"x:1\"}]}";
        HttpServerErrorException refused = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        LlmCallFailed failed = LlmCallFailed.from(refused, Cases.JSON);

        assertThat(failed.status()).isEqualTo(503);
        assertThat(failed.errors()).hasSize(2);
        assertThat(failed.getMessage())
                .isEqualTo("503 Service Unavailable: LLM gateway error: timed out [LLM Gateway]; second [x]");
    }

    @Test
    @DisplayName("a body that is not the WDP shape is named by its size and type, never its text")
    void another_body_is_never_kept() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        HttpServerErrorException refused = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                headers, "<html>upstream down for Bea Example</html>".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThat(LlmCallFailed.from(refused, Cases.JSON).getMessage())
                .isEqualTo("502 Bad Gateway: non-WDP body (42 bytes, text/html)");
    }

    @Test
    @DisplayName("a rejected value in a target is stripped, the field kept; an empty body has no type")
    void a_rejected_value_is_stripped() {
        String body = "{\"errors\":[{\"errorMessage\":\"Input should be a valid string\","
                + "\"target\":\"case.merchantDescriptor:Bea Example Ltd\"}]}";
        HttpClientErrorException refused = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        HttpServerErrorException empty = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

        assertThat(LlmCallFailed.from(refused, Cases.JSON).getMessage())
                .isEqualTo("400 Bad Request: Input should be a valid string [case.merchantDescriptor]");
        assertThat(LlmCallFailed.from(empty, Cases.JSON).getMessage())
                .isEqualTo("502 Bad Gateway: non-WDP body (0 bytes, -)");
    }

    @Test
    @DisplayName("a trace prints the status, the count and the fields; the llm's message that names a key is stored, never logged")
    void a_trace_never_prints_the_llm_s_message() {
        String key = "POCSYN00001/issuer/Bea Example dispute form.pdf";
        String body = "{\"errors\":[{\"errorMessage\":\"Document not found or unreadable: no document at " + key
                + "\",\"target\":\"documents:" + key + "\"},{\"errorMessage\":\"x\",\"target\":null}]}";
        HttpClientErrorException refused = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        LlmCallFailed failed = LlmCallFailed.from(refused, Cases.JSON);
        String printed = ThrowableProxyUtil.asString(new ThrowableProxy(LogSafe.of(new IllegalStateException(
                "wrapped", failed))));

        assertThat(failed.getMessage()).isEqualTo("400 Bad Request: Document not found or unreadable: no document at "
                + key + " [documents]; x []");
        assertThat(failed.logText()).isEqualTo("400 Bad Request: 2 error(s) at documents,-");
        assertThat(printed).contains("LlmCallFailed: 400 Bad Request: 2 error(s) at documents,-")
                .doesNotContain(key).doesNotContain("Bea Example");
        assertThat(LlmCallFailed.from(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8), Cases.JSON).logText())
                .isEqualTo("502 Bad Gateway: non-WDP body (0 bytes, -)");
    }
}
