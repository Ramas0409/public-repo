package com.wp.wdp.chargeback.auto.response.util;

import org.slf4j.MDC;

/**
 * The request's case in the logging context, under the names the llm and the scheduler use - {@code platform},
 * {@code caseNumber}, {@code actionSequence}, {@code runId} - with {@code caseKey}, the {@code P/C/S} the line prints;
 * put where each becomes known, cleared at the end of the request.
 */
public final class LogContext {
    public static final String PLATFORM = "platform";
    public static final String CASE_NUMBER = "caseNumber";
    public static final String ACTION_SEQUENCE = "actionSequence";
    public static final String CASE_KEY = "caseKey";
    public static final String RUN_ID = "runId";
    private static final String NONE = "-";

    private LogContext() {
    }

    public static void putCase(String platform, String caseNumber, String actionSequence) {
        put(PLATFORM, platform);
        put(CASE_NUMBER, caseNumber);
        put(ACTION_SEQUENCE, actionSequence);
        MDC.put(CASE_KEY, orNone(platform) + "/" + orNone(caseNumber) + "/" + orNone(actionSequence));
    }

    /** The run the request serves, as soon as its id is known. */
    public static void putRunId(Object runId) {
        put(RUN_ID, runId == null ? null : String.valueOf(runId));
    }

    public static void clear() {
        MDC.remove(PLATFORM);
        MDC.remove(CASE_NUMBER);
        MDC.remove(ACTION_SEQUENCE);
        MDC.remove(CASE_KEY);
        MDC.remove(RUN_ID);
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? NONE : value;
    }
}
