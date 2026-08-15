package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = ListingEmbeddingRequestBackfillController.class)
public class ListingEmbeddingRequestBackfillExceptionHandler {

    @ExceptionHandler(ListingEmbeddingRequestBackfillException.class)
    ResponseEntity<ApiErrorEnvelope> backfill(
            ListingEmbeddingRequestBackfillException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.kind()) {
            case DISABLED, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String message = switch (exception.kind()) {
            case DISABLED -> "Feature was not found.";
            case NOT_FOUND -> "Listing embedding backfill was not found.";
            case CONFLICT -> "Listing embedding backfill conflicts with this command.";
            case UNAVAILABLE -> "Listing embedding backfill is temporarily unavailable.";
        };
        return error(status, exception.code(), message, request);
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
