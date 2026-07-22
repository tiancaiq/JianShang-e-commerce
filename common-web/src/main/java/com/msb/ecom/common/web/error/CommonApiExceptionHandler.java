package com.msb.ecom.common.web.error;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public final class CommonApiExceptionHandler {

    private static final String UNEXPECTED_EXCEPTION_EVENT = "COMMON_WEB_UNEXPECTED_EXCEPTION";
    private static final Logger log = LoggerFactory.getLogger(CommonApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorEnvelope> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(
                        error.getField(),
                        error.getCode() == null ? "INVALID" : error.getCode().toUpperCase()
                ))
                .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more fields are invalid.",
                fieldErrors,
                request
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorEnvelope> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldError(
                        violation.getPropertyPath().toString(),
                        violation.getConstraintDescriptor().getAnnotation()
                                .annotationType().getSimpleName().toUpperCase()
                ))
                .toList();
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more fields are invalid.",
                fieldErrors,
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorEnvelope> handleMalformedRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "The request body is malformed.",
                List.of(),
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "The request is invalid.",
                List.of(),
                request
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorEnvelope> handleUnauthorized(
            IllegalStateException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED",
                "Authentication is required.",
                List.of(),
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorEnvelope> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String correlationId = CorrelationIdFilter.current(request);
        log.error(
                "event={} correlationId={} exceptionCategory={}",
                UNEXPECTED_EXCEPTION_EVENT,
                correlationId,
                safeExceptionCategory(exception)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(envelope(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred.",
                        List.of(),
                        correlationId
                ));
    }

    // Keeps generic failure diagnostics useful without rendering exception-controlled data.
    private String safeExceptionCategory(Exception exception) {
        return exception instanceof RuntimeException
                ? "RUNTIME_EXCEPTION"
                : "CHECKED_EXCEPTION";
    }

    private ResponseEntity<ApiErrorEnvelope> response(
            HttpStatus status,
            String code,
            String message,
            List<FieldError> fieldErrors,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status)
                .body(envelope(
                        code,
                        message,
                        fieldErrors,
                        CorrelationIdFilter.current(request)
                ));
    }

    private ApiErrorEnvelope envelope(
            String code,
            String message,
            List<FieldError> fieldErrors,
            String correlationId
    ) {
        return new ApiErrorEnvelope(new ApiError(code, message, fieldErrors, correlationId));
    }

}
