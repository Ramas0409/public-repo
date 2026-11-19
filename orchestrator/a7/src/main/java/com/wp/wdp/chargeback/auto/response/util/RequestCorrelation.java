package com.wp.wdp.chargeback.auto.response.util;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/** The correlation id of the request being served: read from the caller's header, held in the logging context. */
public final class RequestCorrelation {
    public static final String HEADER = "v-correlation-id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private RequestCorrelation() {
    }

    /** The current id, or null outside a request. */
    public static String getId() {
        return MDC.get(MDC_KEY);
    }

    /** The caller's id where every line may print it and the llm's usage table may hold it, else null. */
    public static String accepted(String id) {
        return id != null && ACCEPTED.matcher(id).matches() ? id : null;
    }

    /** Makes the id current, a new one where none is given; the id made current. */
    public static String start(String id) {
        String current = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        MDC.put(MDC_KEY, current);
        return current;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
