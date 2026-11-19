package com.wp.wdp.chargeback.auto.response.exception;

import com.wp.wdp.chargeback.auto.response.util.LogText;

/**
 * An {@link IllegalStateException} the service raises itself: its message is what it has always been, so whatever
 * catches, reads or stores it is unchanged (its {@code toString()} still names {@link IllegalStateException}), and a
 * logged trace prints its fixed text alone, never the detail a third party's message appended.
 */
public class ServiceStateException extends IllegalStateException implements LogText {
    private static final long serialVersionUID = 1L;

    private final String logText;

    public ServiceStateException(String text) {
        this(text, text, null);
    }

    public ServiceStateException(String text, Throwable cause) {
        this(text, text, cause);
    }

    public ServiceStateException(String message, String logText, Throwable cause) {
        super(message, cause);
        this.logText = logText;
    }

    /** The fixed text, then a colon and the detail, as the message; the fixed text alone in a trace. */
    public static ServiceStateException appending(String text, String detail, Throwable cause) {
        return new ServiceStateException(text + ": " + detail, text, cause);
    }

    @Override
    public String logText() {
        return logText;
    }

    @Override
    public String toString() {
        return IllegalStateException.class.getName() + ": " + getMessage();
    }
}
