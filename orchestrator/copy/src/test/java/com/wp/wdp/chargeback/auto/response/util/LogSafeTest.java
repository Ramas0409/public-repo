package com.wp.wdp.chargeback.auto.response.util;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.exception.BadRequestException;

class LogSafeTest {
    private static final String QUOTE = "Bea Example of 14 Sample Row signed for the parcel";

    private static String printed(Throwable throwable) {
        return ThrowableProxyUtil.asString(new ThrowableProxy(LogSafe.of(throwable)));
    }

    private static IllegalArgumentException thrownWithAQuote() {
        try {
            throw new IllegalArgumentException("cannot read " + QUOTE);
        } catch (IllegalArgumentException exception) {
            return exception;
        }
    }

    @Test
    @DisplayName("a third party's message is withheld; the class and every frame stay")
    void a_third_party_message_is_withheld() {
        IllegalArgumentException original = thrownWithAQuote();
        Throwable safe = LogSafe.of(original);

        assertThat(safe.getMessage()).isEqualTo("java.lang.IllegalArgumentException: (message withheld)");
        assertThat(safe.getStackTrace()).isEqualTo(original.getStackTrace());
        assertThat(printed(original)).contains("thrownWithAQuote").doesNotContain(QUOTE);
    }

    @Test
    @DisplayName("the chain of causes and the suppressed exceptions stay, each through the same rule")
    void the_chain_stays() {
        IllegalStateException outer = new IllegalStateException("outer " + QUOTE, thrownWithAQuote());
        outer.addSuppressed(new BadRequestException("is not enabled", "reasonCode:VISA 13.7"));
        Throwable safe = LogSafe.of(outer);

        assertThat(safe.getCause().getMessage()).isEqualTo("java.lang.IllegalArgumentException: (message withheld)");
        assertThat(safe.getSuppressed()[0].getMessage())
                .isEqualTo("com.wp.wdp.chargeback.auto.response.exception.BadRequestException: is not enabled");
        assertThat(printed(outer)).contains("Caused by:").contains("Suppressed:").doesNotContain(QUOTE);
    }

    @Test
    @DisplayName("the service's own exception keeps its message")
    void an_own_message_is_kept() {
        assertThat(LogSafe.message(new BadRequestException("is not enabled", "reasonCode:x"))).isEqualTo("is not enabled");
    }

    @Test
    @DisplayName("a database error is its SQLSTATE, never its statement or the failing row")
    void a_database_error_is_its_sqlstate() {
        SQLException driver = new SQLException("ERROR: violates check constraint. Detail: Failing row (" + QUOTE + ")",
                "23514");
        DataIntegrityViolationException spring = new DataIntegrityViolationException(
                "could not execute statement [insert into x values ('" + QUOTE + "')]", driver);

        assertThat(LogSafe.message(driver)).isEqualTo("sqlstate=23514");
        assertThat(LogSafe.message(spring)).isEqualTo("sqlstate=23514");
        assertThat(printed(spring)).doesNotContain(QUOTE);
    }

    @Test
    @DisplayName("a JSON parse error is its location, never its source text")
    void a_json_error_is_its_location() {
        Exception parse = null;
        try {
            new ObjectMapper().readTree("{\"quote\": \"" + QUOTE + "\" oops}");
        } catch (Exception exception) {
            parse = exception;
        }
        assertThat(LogSafe.message(parse)).matches("line \\d+, column \\d+");
        assertThat(printed(parse)).doesNotContain(QUOTE);
    }

    @Test
    @DisplayName("an HTTP client error is its status, or the host it could not reach")
    void an_http_error_is_its_status_or_host() {
        HttpServerErrorException refused = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", HttpHeaders.EMPTY, QUOTE.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        ResourceAccessException unreachable = new ResourceAccessException(
                "I/O error on POST request for \"http://llm.example:8000/scenario/nominate\": " + QUOTE);

        assertThat(LogSafe.message(refused)).isEqualTo("status=503");
        assertThat(LogSafe.message(unreachable)).isEqualTo("host=llm.example");
    }

    @Test
    @DisplayName("a structural JDK error keeps its message, which names only the code")
    void a_structural_error_keeps_its_message() {
        assertThat(LogSafe.message(new ArrayIndexOutOfBoundsException("Index 5 out of bounds for length 3")))
                .isEqualTo("Index 5 out of bounds for length 3");
        assertThat(LogSafe.of(null)).isNull();
    }

    @Test
    @DisplayName("no class is @Data, and none has a @ToString that could print it whole")
    void no_class_is_data_and_none_prints_whole() throws Exception {
        java.util.Set<String> mayPrintWhole = java.util.Set.of();
        try (java.util.stream.Stream<java.nio.file.Path> sources = java.nio.file.Files.walk(
                java.nio.file.Path.of("src", "main", "java"))) {
            for (java.nio.file.Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = java.nio.file.Files.readString(source);
                assertThat(text).as(source.toString()).doesNotContain("@Data").doesNotContain("lombok.Data");
                if (!mayPrintWhole.contains(source.getFileName().toString())) {
                    assertThat(text).as(source.toString()).doesNotContain("@ToString");
                }
            }
        }
    }

    @Test
    @DisplayName("the office's encoder, logstash-logback-encoder 8.1, writes the context keys and the log-safe trace")
    void the_logstash_encoder_writes_the_context_and_the_clean_trace() throws Exception {
        ch.qos.logback.classic.LoggerContext context = (ch.qos.logback.classic.LoggerContext)
                org.slf4j.LoggerFactory.getILoggerFactory();
        net.logstash.logback.encoder.LogstashEncoder encoder = new net.logstash.logback.encoder.LogstashEncoder();
        encoder.setContext(context);
        encoder.start();
        try {
            ch.qos.logback.classic.Logger logger = context.getLogger("com.wp.wdp.chargeback.auto.response.logstash.test");
            Throwable failure;
            try {
                throw new IllegalStateException("cannot read Bea Example");
            } catch (IllegalStateException caught) {
                failure = caught;
            }
            ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent(
                    getClass().getName(), logger, ch.qos.logback.classic.Level.WARN, "event=test.failed", LogSafe.of(failure),
                    null);
            event.setMDCPropertyMap(java.util.Map.of("correlationId", "SYN-IDEM-ENC-1", "caseKey", "NAP/POCSYN00001/01"));
            com.fasterxml.jackson.databind.JsonNode line = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(encoder.encode(event));
            assertThat(line.path("message").asText()).isEqualTo("event=test.failed");
            assertThat(line.path("level").asText()).isEqualTo("WARN");
            assertThat(line.path("level_value").asInt()).isEqualTo(30000);
            assertThat(line.path("logger_name").asText()).isEqualTo("com.wp.wdp.chargeback.auto.response.logstash.test");
            assertThat(line.path("correlationId").asText()).isEqualTo("SYN-IDEM-ENC-1");
            assertThat(line.path("caseKey").asText()).isEqualTo("NAP/POCSYN00001/01");
            assertThat(line.has("@timestamp")).isTrue();
            assertThat(line.path("@version").asText()).isEqualTo("1");
            assertThat(line.path("stack_trace").asText())
                    .contains("LogSafeThrowable: java.lang.IllegalStateException: (message withheld)")
                    .contains("the_logstash_encoder_writes_the_context_and_the_clean_trace")
                    .doesNotContain("Bea Example");
        } finally {
            encoder.stop();
        }
    }
}

