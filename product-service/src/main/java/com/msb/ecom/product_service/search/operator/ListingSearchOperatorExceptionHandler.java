package com.msb.ecom.product_service.search.operator;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = ListingSearchOperatorController.class)
public class ListingSearchOperatorExceptionHandler {

    @ExceptionHandler(ListingSearchOperatorFeatureDisabledException.class)
    ResponseEntity<ApiErrorEnvelope> disabled(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "FEATURE_DISABLED",
                "Feature was not found.", request);
    }

    @ExceptionHandler(ListingSearchOperatorNotFoundException.class)
    ResponseEntity<ApiErrorEnvelope> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "VECTOR_REBUILD_NOT_FOUND",
                "Listing search rebuild was not found.", request);
    }

    @ExceptionHandler(ListingSearchOperatorConflictException.class)
    ResponseEntity<ApiErrorEnvelope> conflict(
            ListingSearchOperatorConflictException exception,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.code(),
                "Listing search rebuild conflicts with this command.", request);
    }

    @ExceptionHandler({
            ListingSearchOperatorUnavailableException.class,
            AuthServiceClient.DependencyUnavailableException.class
    })
    ResponseEntity<ApiErrorEnvelope> unavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "VECTOR_REBUILD_UNAVAILABLE",
                "Listing search rebuild is temporarily unavailable.", request);
    }

    @ExceptionHandler({
            AuthServiceClient.AuthenticationException.class,
            AuthenticationException.class
    })
    ResponseEntity<ApiErrorEnvelope> unauthenticated(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                "Authentication is required.", request);
    }

    @ExceptionHandler(ListingAuthorizationException.class)
    ResponseEntity<ApiErrorEnvelope> forbidden(HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "LISTING_FORBIDDEN",
                "Platform admin access is required.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorEnvelope> invalid(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "LISTING_INVALID_REQUEST",
                "Request is invalid.", request);
    }

    private ResponseEntity<ApiErrorEnvelope> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorEnvelope(new ApiError(
                code,
                message,
                List.of(),
                CorrelationIdFilter.current(request))));
    }
}
