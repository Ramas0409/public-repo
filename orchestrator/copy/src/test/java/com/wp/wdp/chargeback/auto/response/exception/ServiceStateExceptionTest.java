package com.wp.wdp.chargeback.auto.response.exception;

import java.io.IOException;

import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;

class ServiceStateExceptionTest {
    private static final String QUOTE = "Bea Example of 14 Sample Row signed for the parcel";

    private static String printed(Throwable throwable) {
        return ThrowableProxyUtil.asString(new ThrowableProxy(LogSafe.of(throwable)));
    }

    private static ServiceStateException raiseFromTheService() {
        IOException library = new IOException("cannot embed the page that reads " + QUOTE);
        try {
            throw ServiceStateException.appending("the letter could not be rendered to PDF", library.getMessage(),
                    library);
        } catch (ServiceStateException exception) {
            return exception;
        }
    }

    @Test
    @DisplayName("an error the service raises prints its fixed text in the trace, never the third party's appended message")
    void the_trace_prints_the_fixed_text() {
        String printed = printed(raiseFromTheService());

        assertThat(printed).contains("ServiceStateException: the letter could not be rendered to PDF")
                .contains("raiseFromTheService").contains("java.io.IOException: (message withheld)")
                .doesNotContain(QUOTE);
    }

    @Test
    @DisplayName("its class, message and text stored are what an IllegalStateException's were")
    void what_is_read_and_stored_is_unchanged() {
        ServiceStateException raised = raiseFromTheService();
        String before = new IllegalStateException("the letter could not be rendered to PDF: cannot embed the page "
                + "that reads " + QUOTE).toString();

        assertThat(raised).isInstanceOf(IllegalStateException.class);
        assertThat(raised.getMessage()).isEqualTo("the letter could not be rendered to PDF: cannot embed the page "
                + "that reads " + QUOTE);
        assertThat(raised.toString()).isEqualTo(before);
        assertThat(new ServiceStateException("the nominate answer is not JSON: x", "y", null).toString())
                .isEqualTo("java.lang.IllegalStateException: the nominate answer is not JSON: x");
    }

    @Test
    @DisplayName("a message with a document key in its fixed part keeps it stored and logs the clean text")
    void a_separate_log_text_is_printed() {
        ServiceStateException raised = new ServiceStateException("the document store did not answer for C/issuer/"
                + "Bea Example.pdf: gone", "the document store did not answer", new IOException("gone"));

        assertThat(raised.getMessage()).contains("Bea Example");
        assertThat(printed(raised)).contains("ServiceStateException: the document store did not answer")
                .doesNotContain("Bea Example");
    }
}
