package com.msb.ecom.product_service.search.hybrid;

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

@RestControllerAdvice(assignableTypes = InternalAgentHybridSearchController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListingHybridSearchExceptionHandler {

    @ExceptionHandler(ListingHybridSearchFeatureDisabledException.class)
    public ResponseEntity<ApiErrorEnvelope> disabled(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "FEATURE_DISABLED",
                "The requested capability is not available.", request);
    }

    @ExceptionHandler(ListingAuthorizationException.class)
    public ResponseEntity<ApiErrorEnvelope> authenticationRequired(HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "AGENT_INTERNAL_AUTHENTICATION_REQUIRED",
                "Agent service authentication is required.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorEnvelope> invalid(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The marketplace hybrid search request is invalid.", request);
    }

    @ExceptionHandler(ListingHybridSearchIdentityMismatchException.class)
    public ResponseEntity<ApiErrorEnvelope> identityMismatch(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "MARKETPLACE_HYBRID_SEARCH_IDENTITY_MISMATCH",
                "The marketplace hybrid search identity is incompatible.", request);
    }

    @ExceptionHandler(ListingHybridSearchUnavailableException.class)
    public ResponseEntity<ApiErrorEnvelope> unavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "MARKETPLACE_HYBRID_SEARCH_UNAVAILABLE",
                "Marketplace hybrid search is temporarily unavailable.", request);
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
