package com.wp.wdp.chargeback.auto.response.util;

/**
 * The log-safe form of one throwable in a chain: its message names the original class and the clean part of the
 * original message, and its stack trace is the original's frame for frame.
 */
public class LogSafeThrowable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LogSafeThrowable(String message) {
        super(message);
    }
}
