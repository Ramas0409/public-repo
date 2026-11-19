package com.wp.wdp.chargeback.auto.response.exception;

import com.wp.wdp.chargeback.auto.response.util.LogText;

/**
 * An {@link IllegalArgumentException} the service raises itself with its own fixed text: its message and its
 * {@code toString()} are what they have always been, and a logged trace prints that text.
 */
public class ServiceArgumentException extends IllegalArgumentException implements LogText {
    private static final long serialVersionUID = 1L;

    public ServiceArgumentException(String text) {
        super(text);
    }

    @Override
    public String logText() {
        return getMessage();
    }

    @Override
    public String toString() {
        return IllegalArgumentException.class.getName() + ": " + getMessage();
    }
}
