package com.wp.wdp.chargeback.auto.response.util;

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The log-safe form of a throwable, which every logged throwable goes through: the same chain of causes and
 * suppressed throwables, every class name and every stack frame, and each message kept only where it is clean - an
 * exception's own {@link LogText}, the message of an exception the service defines, a database error's SQLSTATE, a
 * validation error's count and field names, an HTTP client error's status or host, a JSON parse error's location, or a
 * JDK error whose message names only the code - else {@value #WITHHELD}, since a third party's message can carry the
 * text it was given.
 */
public final class LogSafe {
    public static final String WITHHELD = "(message withheld)";
    private static final String OWN_PACKAGE = "com.wp.wdp.chargeback.auto.response.";
    private static final Pattern URL = Pattern.compile("\"(https?://[^\"\\s]+)\"");
    private static final List<Class<? extends Throwable>> STRUCTURAL = List.of(NullPointerException.class,
            ClassCastException.class, IndexOutOfBoundsException.class, ArithmeticException.class,
            VirtualMachineError.class);

    private LogSafe() {
    }

    /** The throwable's log-safe form, or null where there is none. */
    public static Throwable of(Throwable throwable) {
        return throwable == null ? null : copy(throwable, new IdentityHashMap<>());
    }

    private static LogSafeThrowable copy(Throwable original, Map<Throwable, LogSafeThrowable> seen) {
        LogSafeThrowable copy = new LogSafeThrowable(original.getClass().getName() + ": " + message(original));
        seen.put(original, copy);
        copy.setStackTrace(original.getStackTrace());
        Throwable cause = original.getCause();
        if (cause != null && !seen.containsKey(cause)) {
            copy.initCause(copy(cause, seen));
        }
        for (Throwable suppressed : original.getSuppressed()) {
            if (!seen.containsKey(suppressed)) {
                copy.addSuppressed(copy(suppressed, seen));
            }
        }
        return copy;
    }

    /** What a logged trace prints of one throwable's message. */
    public static String message(Throwable throwable) {
        if (throwable instanceof LogText) {
            return String.valueOf(((LogText) throwable).logText());
        }
        if (throwable.getClass().getName().startsWith(OWN_PACKAGE)) {
            return String.valueOf(throwable.getMessage());
        }
        if (throwable instanceof SQLException) {
            return "sqlstate=" + orDash(((SQLException) throwable).getSQLState());
        }
        String name = throwable.getClass().getName();
        if (name.startsWith("org.springframework.dao.") || name.startsWith("org.hibernate.")
                || name.startsWith("jakarta.persistence.")) {
            return "sqlstate=" + orDash(sqlState(throwable));
        }
        if (throwable instanceof BindException) {
            return fields(((BindException) throwable).getFieldErrors());
        }
        if (throwable instanceof ConstraintViolationException) {
            List<String> paths = new ArrayList<>();
            for (ConstraintViolation<?> violation : ((ConstraintViolationException) throwable).getConstraintViolations()) {
                paths.add(String.valueOf(violation.getPropertyPath()));
            }
            return paths.size() + " violation(s) at " + String.join(",", paths);
        }
        if (throwable instanceof RestClientResponseException) {
            return "status=" + ((RestClientResponseException) throwable).getStatusCode().value();
        }
        if (throwable instanceof ResourceAccessException) {
            return "host=" + orDash(host(throwable.getMessage()));
        }
        if (throwable instanceof JsonProcessingException) {
            JsonLocation location = ((JsonProcessingException) throwable).getLocation();
            return location == null ? "location unknown"
                    : "line " + location.getLineNr() + ", column " + location.getColumnNr();
        }
        for (Class<? extends Throwable> structural : STRUCTURAL) {
            if (structural.isInstance(throwable) && name.startsWith("java.")) {
                return String.valueOf(throwable.getMessage());
            }
        }
        return WITHHELD;
    }

    private static String fields(List<FieldError> errors) {
        List<String> names = new ArrayList<>();
        for (FieldError error : errors) {
            names.add(error.getField());
        }
        return errors.size() + " field error(s) at " + String.join(",", names);
    }

    private static String sqlState(Throwable throwable) {
        Throwable cause = throwable;
        Map<Throwable, Boolean> visited = new IdentityHashMap<>();
        while (cause != null && visited.put(cause, Boolean.TRUE) == null) {
            if (cause instanceof SQLException) {
                return ((SQLException) cause).getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    private static String host(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = URL.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return URI.create(matcher.group(1)).getHost();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
