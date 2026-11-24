package org.study.usageapi.api;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.study.usageapi.service.RequestIdFilter;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        var response = ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.clientMessage(), RequestIdFilter.currentRequestId()));
        if (exception.status() == HttpStatus.TOO_MANY_REQUESTS) {
            return ResponseEntity.status(exception.status())
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return ResponseEntity.unprocessableEntity().body(error("invalid_request",
                "The request does not match the required format."));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader() {
        return ResponseEntity.badRequest().body(error("invalid_request", "A required request header is missing."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch() {
        return ResponseEntity.badRequest().body(error("invalid_request", "A request parameter is invalid."));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return ResponseEntity.status(status)
                .body(error(status == HttpStatus.SERVICE_UNAVAILABLE ? "service_unavailable" : "request_failed",
                        "The request could not be completed."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("conflict", "The request conflicts with existing data."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        LOGGER.error("Request {} failed with {}", RequestIdFilter.currentRequestId(),
                exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(error("internal_error", "The request could not be completed."));
    }

    private ApiError error(String code, String message) {
        return new ApiError(code, message, RequestIdFilter.currentRequestId());
    }
}
