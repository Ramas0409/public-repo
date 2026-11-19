package com.wp.wdp.chargeback.auto.response.exception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import com.wp.wdp.chargeback.auto.response.util.LogSafe;

/**
 * The refusals of every endpoint, in the WDP services' shape: {@code {"errors": [{errorMessage, target}]}}.
 * A field error's target is {@code field:rejectedValue}, one error per field. A 4xx is one WARN line naming the
 * fields refused, never a value and no stack trace; a body that cannot be read is the caller's too, one WARN line with
 * the JSON location alone; any other 500 is one ERROR line with the log-safe stack trace, and anything not named here
 * is a 500 "system error".
 */
@Order(1)
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final String SYSTEM_ERROR = "System Error";
    private static final String SYSTEM_ERROR_MESSAGE =
            "A system error has occurred Please contact support or try your request at a later time";
    private static final String URL_NOT_FOUND = "Not Found";
    private static final String METHOD_NOT_ALLOWED = "Method Not ALLOWED";
    private static final String MESSAGE_CONVERTER = "Message Converter";
    private static final String DOCUMENTS = "documents";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<StandardErrorResponse> requestHandlingNoHandlerFound(NoHandlerFoundException e) {
        refused(HttpStatus.NOT_FOUND, 1, List.of());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(StandardErrorResponse.of(e.getMessage(), URL_NOT_FOUND));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<StandardErrorResponse> httpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("event=request.refused status=500 reason=unreadable-body location={}", location(e));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(StandardErrorResponse.of(e.getMessage(), MESSAGE_CONVERTER));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<StandardErrorResponse> methodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        refused(HttpStatus.BAD_REQUEST, 1, List.of(e.getName()));
        return ResponseEntity.badRequest().body(StandardErrorResponse.of(e.getMessage(), e.getName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<StandardErrorResponse> methodArgumentNotValidException(MethodArgumentNotValidException e) {
        List<StandardDisplayError> errors = new ArrayList<>();
        Set<String> processedFields = new LinkedHashSet<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            if (processedFields.add(error.getField())) {
                errors.add(new StandardDisplayError(error.getDefaultMessage(),
                        error.getField() + ":" + error.getRejectedValue()));
            }
        }
        refused(HttpStatus.BAD_REQUEST, errors.size(), List.copyOf(processedFields));
        return ResponseEntity.badRequest().body(new StandardErrorResponse(inFieldOrder(errors)));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<StandardErrorResponse> constraintViolationException(ConstraintViolationException e) {
        List<StandardDisplayError> errors = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            errors.add(new StandardDisplayError(violation.getMessage(),
                    violation.getPropertyPath() + ":" + violation.getInvalidValue()));
            fields.add(String.valueOf(violation.getPropertyPath()));
        }
        refused(HttpStatus.BAD_REQUEST, errors.size(), fields);
        return ResponseEntity.badRequest().body(new StandardErrorResponse(inFieldOrder(errors)));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<StandardErrorResponse> httpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException e) {
        refused(HttpStatus.METHOD_NOT_ALLOWED, 1, List.of());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(StandardErrorResponse.of(e.getMessage(), METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<StandardErrorResponse> badRequestException(BadRequestException e) {
        refused(HttpStatus.BAD_REQUEST, 1, field(e.getTarget()));
        return ResponseEntity.badRequest().body(StandardErrorResponse.of(e.getMessage(), e.getTarget()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<StandardErrorResponse> notFoundException(NotFoundException e) {
        refused(HttpStatus.NOT_FOUND, 1, field(e.getTarget()));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(StandardErrorResponse.of(e.getMessage(), e.getTarget()));
    }

    /** A replay-only read that would have needed the model: its answer names the document by its key, as before. */
    @ExceptionHandler(ModelCallRefused.class)
    public ResponseEntity<StandardErrorResponse> modelCallRefused(ModelCallRefused e) {
        refused(HttpStatus.CONFLICT, 1, List.of(DOCUMENTS));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(StandardErrorResponse.of(e.responseMessage(), e.getTarget()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<StandardErrorResponse> conflictException(ConflictException e) {
        refused(HttpStatus.CONFLICT, 1, field(e.getTarget()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(StandardErrorResponse.of(e.getMessage(), e.getTarget()));
    }

    /** Every other runtime exception: the log-safe stack trace is logged, the caller is told only that the system failed. */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<StandardErrorResponse> handleRuntimeException(RuntimeException e) {
        log.error("event=request.failed status=500 reason=unexpected error={}", e.getClass().getSimpleName(),
                LogSafe.of(e));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(StandardErrorResponse.of(SYSTEM_ERROR_MESSAGE, SYSTEM_ERROR));
    }

    private static void refused(HttpStatus status, int errors, List<String> fields) {
        List<String> named = new ArrayList<>(new LinkedHashSet<>(fields));
        named.sort(null);
        log.warn("event=request.refused status={} errors={} fields={}", status.value(), errors,
                named.isEmpty() ? "-" : String.join(",", named));
    }

    /** Where the body stopped parsing, as {@code line:column}, or a dash where the converter names no place. */
    private static String location(Throwable e) {
        Set<Throwable> seen = new HashSet<>();
        for (Throwable cause = e; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException && ((JsonProcessingException) cause).getLocation() != null) {
                JsonLocation location = ((JsonProcessingException) cause).getLocation();
                return location.getLineNr() + ":" + location.getColumnNr();
            }
        }
        return "-";
    }

    /** The errors sorted by the field their target names, then by message, so a 400 reads the same on every run. */
    private static List<StandardDisplayError> inFieldOrder(List<StandardDisplayError> errors) {
        List<StandardDisplayError> sorted = new ArrayList<>(errors);
        sorted.sort(Comparator.comparing((StandardDisplayError error) -> fieldOf(error.getTarget()))
                .thenComparing(error -> String.valueOf(error.getErrorMessage())));
        return sorted;
    }

    private static String fieldOf(String target) {
        if (target == null) {
            return "";
        }
        int separator = target.indexOf(':');
        return separator < 0 ? target : target.substring(0, separator);
    }

    /** The field a refusal's target names: the part before its value, never the value. */
    private static List<String> field(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        int separator = target.indexOf(':');
        return List.of(separator < 0 ? target : target.substring(0, separator));
    }
}
