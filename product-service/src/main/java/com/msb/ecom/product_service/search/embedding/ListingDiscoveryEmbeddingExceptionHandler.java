package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = ListingDiscoveryEmbeddingSourceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListingDiscoveryEmbeddingExceptionHandler {

    @ExceptionHandler(ListingDiscoveryEmbeddingFeatureDisabledException.class)
    public ResponseEntity<ApiErrorEnvelope> disabled(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "FEATURE_DISABLED",
                "The requested capability is not available.", request);
    }

    @ExceptionHandler(ListingAuthorizationException.class)
    public ResponseEntity<ApiErrorEnvelope> unauthorized(HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "AGENT_INTERNAL_AUTHENTICATION_REQUIRED",
                "Agent service authentication is required.", request);
    }

    @ExceptionHandler(ListingDiscoveryEmbeddingSourceNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "LISTING_DISCOVERY_EMBEDDING_SOURCE_NOT_FOUND",
                "Listing discovery embedding source was not found.", request);
    }

    @ExceptionHandler(ListingDiscoveryEmbeddingStaleException.class)
    public ResponseEntity<ApiErrorEnvelope> stale(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "LISTING_DISCOVERY_EMBEDDING_STALE",
                "The listing discovery embedding result is stale.", request);
    }

    @ExceptionHandler(ListingDiscoveryEmbeddingIdentityConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> identityConflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "LISTING_DISCOVERY_EMBEDDING_IDENTITY_CONFLICT",
                "The listing discovery embedding identity conflicts.", request);
    }

    @ExceptionHandler(ListingDiscoveryEmbeddingIdempotencyConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> idempotencyConflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "LISTING_DISCOVERY_EMBEDDING_IDEMPOTENCY_CONFLICT",
                "The listing discovery embedding result conflicts.", request);
    }

    @ExceptionHandler(ListingDiscoveryEmbeddingUnavailableException.class)
    public ResponseEntity<ApiErrorEnvelope> unavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "LISTING_DISCOVERY_EMBEDDING_UNAVAILABLE",
                "Listing discovery embedding source is temporarily unavailable.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorEnvelope> invalid(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The listing discovery embedding request is invalid.", request);
    }

    private ResponseEntity<ApiErrorEnvelope> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiErrorEnvelope(new ApiError(
                        code,
                        message,
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }
}
