package com.wp.wdp.chargeback.auto.response.exception;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientResponseException;
import com.wp.wdp.chargeback.auto.response.util.LogText;

/**
 * The llm service refused a call: its status and the errors of its WDP error body in one message, each error's target
 * named by its field alone, never its value; a body that is not the WDP shape is named by its size and type, never its
 * text. The message is what the run stores; a logged trace prints its log text instead - the status, the number of
 * errors and their field names - since the llm's own message can name a document.
 */
public final class LlmCallFailed extends RuntimeException implements LogText {
    private final int status;
    private final List<StandardDisplayError> errors;
    private final String logText;

    private LlmCallFailed(int status, String statusText, List<StandardDisplayError> errors, int bodyBytes,
                          String contentType) {
        super(status + " " + statusText + ": " + describe(errors, bodyBytes, contentType));
        this.status = status;
        this.errors = List.copyOf(errors);
        this.logText = status + " " + statusText + ": " + (errors.isEmpty() ? describe(errors, bodyBytes, contentType)
                : errors.size() + " error(s) at " + fields(errors));
    }

    public static LlmCallFailed from(RestClientResponseException exception, ObjectMapper objectMapper) {
        String body = exception.getResponseBodyAsString();
        List<StandardDisplayError> errors = new ArrayList<>();
        try {
            StandardErrorResponse parsed = objectMapper.readValue(body, StandardErrorResponse.class);
            if (parsed != null && parsed.getErrors() != null) {
                errors.addAll(parsed.getErrors());
            }
        } catch (Exception unreadable) {
        }
        MediaType type = exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getContentType();
        return new LlmCallFailed(exception.getStatusCode().value(), exception.getStatusText(), errors,
                exception.getResponseBodyAsByteArray().length, type == null ? null : type.toString());
    }

    public int status() {
        return status;
    }

    public List<StandardDisplayError> errors() {
        return errors;
    }

    @Override
    public String logText() {
        return logText;
    }

    private static String fields(List<StandardDisplayError> errors) {
        List<String> names = new ArrayList<>();
        for (StandardDisplayError error : errors) {
            String name = field(error.getTarget());
            names.add(name.isEmpty() ? "-" : name);
        }
        return String.join(",", names);
    }

    private static String describe(List<StandardDisplayError> errors, int bodyBytes, String contentType) {
        if (errors.isEmpty()) {
            return "non-WDP body (" + bodyBytes + " bytes, " + (contentType == null ? "-" : contentType) + ")";
        }
        List<String> parts = new ArrayList<>();
        for (StandardDisplayError error : errors) {
            parts.add(error.getErrorMessage() + " [" + field(error.getTarget()) + "]");
        }
        return String.join("; ", parts);
    }

    private static String field(String target) {
        if (target == null) {
            return "";
        }
        int separator = target.indexOf(':');
        return separator < 0 ? target : target.substring(0, separator);
    }
}
