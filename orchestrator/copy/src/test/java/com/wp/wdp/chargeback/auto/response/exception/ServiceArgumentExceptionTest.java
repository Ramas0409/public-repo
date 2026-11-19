package com.wp.wdp.chargeback.auto.response.exception;

import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;

class ServiceArgumentExceptionTest {
    @Test
    @DisplayName("an argument error the service raises prints its own text; its class and text stored are unchanged")
    void its_own_text_is_printed() {
        ServiceArgumentException raised = new ServiceArgumentException("a catalogue needs its reason code");

        assertThat(ThrowableProxyUtil.asString(new ThrowableProxy(LogSafe.of(raised))))
                .contains("ServiceArgumentException: a catalogue needs its reason code");
        assertThat(raised).isInstanceOf(IllegalArgumentException.class);
        assertThat(raised.toString()).isEqualTo("java.lang.IllegalArgumentException: a catalogue needs its reason code");
    }
}
