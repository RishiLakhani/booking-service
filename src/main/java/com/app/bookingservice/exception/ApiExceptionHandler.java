package com.app.bookingservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Set;

/**
 * Every error leaves the API as {"error": "<code>", "message": "..."} with a short, general message.
 * Spring MVC's own exceptions (bad JSON, missing header, unknown path, ...) arrive via handleExceptionInternal.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Postgres lock_timeout (55P03), which Spring leaves uncategorized. (statement_timeout arrives as QueryTimeoutException.) */
    private static final Set<String> TIMEOUT_SQL_STATES = Set.of("55P03");

    @ExceptionHandler(ReservationDeclinedException.class)
    public ResponseEntity<ApiError> declined(ReservationDeclinedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.reason().code(), e.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> invalidRequest(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid value for '" + e.getName() + "'");
    }

    /**
     * Database temporarily unavailable or overloaded (e.g. no pooled connection within the timeout,
     * lock wait timeout, deadlock victim): 503 with Retry-After, so clients know to retry.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ApiError> databaseUnavailable(Exception e) {
        log.warn("Database temporarily unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiError("service-unavailable", "service is busy, retry shortly"));
    }

    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<ApiError> uncategorizedSql(UncategorizedSQLException e) {
        var sqlException = e.getSQLException();
        if (sqlException != null && TIMEOUT_SQL_STATES.contains(sqlException.getSQLState())) {
            return databaseUnavailable(e);
        }
        return unexpected(e);
    }

    /** Anything unexpected: logged in full, returned without internals. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected server error");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        return ResponseEntity.status(statusCode).headers(headers)
                .body(new ApiError(codeFor(statusCode), messageFor(ex, statusCode)));
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiError(codeFor(status), message));
    }

    private static String messageFor(Exception ex, HttpStatusCode status) {
        return switch (ex) {
            case ResponseStatusException e when e.getReason() != null -> e.getReason();
            case MethodArgumentNotValidException e -> "request body is missing required fields or has invalid values";
            case HandlerMethodValidationException e -> "request has invalid values";
            case HttpMessageNotReadableException e -> "request body is missing, is not valid JSON, or has a value of the wrong type";
            case MissingRequestHeaderException e -> "missing required header: " + e.getHeaderName();
            case ErrorResponse e when status.value() == 404 -> "resource not found";
            default -> HttpStatus.valueOf(status.value()).getReasonPhrase().toLowerCase();
        };
    }

    static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "invalid-request";
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 409 -> "conflict";
            case 415 -> "unsupported-media-type";
            case 503 -> "service-unavailable";
            default -> status.is5xxServerError() ? "internal-error" : "error";
        };
    }

    public record ApiError(String error, String message) {
    }
}
