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
}
