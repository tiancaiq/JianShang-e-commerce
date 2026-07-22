package com.msb.ecom.product_service.reports;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = ListingReportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListingReportExceptionHandler {

    @ExceptionHandler({ListingReportFeatureDisabledException.class, ListingReportNotFoundException.class})
    public ResponseEntity<ApiErrorEnvelope> notFound(HttpServletRequest request) {
        return error(
                HttpStatus.NOT_FOUND,
                "LISTING_REPORT_NOT_FOUND",
                "Listing report was not found.",
                request);
    }

    @ExceptionHandler(ListingAuthorizationException.class)
    public ResponseEntity<ApiErrorEnvelope> forbidden(HttpServletRequest request) {
        return error(
                HttpStatus.FORBIDDEN,
                "LISTING_REPORT_FORBIDDEN",
                "Authenticated report access is required.",
                request);
    }

    @ExceptionHandler(AuthServiceClient.AuthenticationException.class)
    public ResponseEntity<ApiErrorEnvelope> authenticationRequired(HttpServletRequest request) {
        return error(
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "Authentication is required.",
                request);
    }

    @ExceptionHandler({ListingReportInvalidRequestException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorEnvelope> invalid(HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "LISTING_REPORT_INVALID_REQUEST",
                "The listing report request is invalid.",
                request);
    }

    @ExceptionHandler(ListingReportPayloadTooLargeException.class)
    public ResponseEntity<ApiErrorEnvelope> payloadTooLarge(HttpServletRequest request) {
        return error(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "LISTING_REPORT_PAYLOAD_TOO_LARGE",
                "The listing report request exceeds 8192 bytes.",
                request);
    }

    @ExceptionHandler(ListingReportIdempotencyConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> idempotencyConflict(HttpServletRequest request) {
        return error(
                HttpStatus.CONFLICT,
                "LISTING_REPORT_IDEMPOTENCY_CONFLICT",
                "The idempotency key was already used with a different request.",
                request);
    }

    @ExceptionHandler(ListingReportRateLimitException.class)
    public ResponseEntity<ApiErrorEnvelope> rateLimited(
            ListingReportRateLimitException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(body(
                        "LISTING_REPORT_RATE_LIMITED",
                        "Too many listing reports were accepted. Retry later.",
                        request));
    }

    @ExceptionHandler({
            ListingReportUnavailableException.class,
            AuthServiceClient.DependencyUnavailableException.class
    })
    public ResponseEntity<ApiErrorEnvelope> unavailable(HttpServletRequest request) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "LISTING_REPORT_UNAVAILABLE",
                "Listing report intake is temporarily unavailable.",
                request);
    }

    private ResponseEntity<ApiErrorEnvelope> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(code, message, request));
    }

    private ApiErrorEnvelope body(String code, String message, HttpServletRequest request) {
        return new ApiErrorEnvelope(new ApiError(
                code,
                message,
                List.of(),
                CorrelationIdFilter.current(request)));
    }
}
