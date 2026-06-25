package com.msb.ecom.product_service.listing;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ListingExceptionHandler {

    @ExceptionHandler(ListingAuthorizationException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingAuthorization(
            ListingAuthorizationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_FORBIDDEN",
                        exception.getMessage(),
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleCategoryNotFound(
            CategoryNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "CATEGORY_NOT_FOUND",
                        "Category was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(ListingNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingNotFound(
            ListingNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_NOT_FOUND",
                        "Listing was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(ListingMediaNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingMediaNotFound(
            ListingMediaNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_MEDIA_NOT_FOUND",
                        "Listing media was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInvalidListingRequest(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_INVALID_REQUEST",
                        exception.getMessage(),
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }
}
