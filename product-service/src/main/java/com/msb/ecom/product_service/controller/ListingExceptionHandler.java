package com.msb.ecom.product_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.product_service.model.CategoryNotFoundException;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.model.ListingMediaAccessDeniedException;
import com.msb.ecom.product_service.model.ListingMediaNotFoundException;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import com.msb.ecom.product_service.model.ListingVersionConflictException;
import com.msb.ecom.product_service.model.ModerationCaseNotFoundException;
import com.msb.ecom.product_service.model.ModerationCaseVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
@Slf4j
public class ListingExceptionHandler {

    @ExceptionHandler(ListingAuthorizationException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingAuthorization(
            ListingAuthorizationException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Denied listing action path={} correlationId={} reason={}",
                request.getRequestURI(), correlationId, exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_FORBIDDEN",
                        exception.getMessage(),
                        List.of(),
                        correlationId)));
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
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Listing media lookup failed path={} correlationId={}", request.getRequestURI(), correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_MEDIA_NOT_FOUND",
                        "Listing media was not found.",
                        List.of(),
                        correlationId)));
    }

    @ExceptionHandler(ListingMediaAccessDeniedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingMediaAccessDenied(
            ListingMediaAccessDeniedException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Listing media storage access denied path={} correlationId={} reason={}",
                request.getRequestURI(), correlationId, exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_MEDIA_STORAGE_ACCESS_DENIED",
                        "Listing media storage could not be read.",
                        List.of(),
                        correlationId)));
    }

    @ExceptionHandler(ListingVersionConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingVersionConflict(
            ListingVersionConflictException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Listing version conflict path={} correlationId={}", request.getRequestURI(), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_VERSION_CONFLICT",
                        "Listing draft was changed by another request.",
                        List.of(),
                        correlationId)));
    }

    @ExceptionHandler(ModerationCaseVersionConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleModerationCaseVersionConflict(
            ModerationCaseVersionConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "MODERATION_CASE_VERSION_CONFLICT",
                        "Moderation case was changed by another request.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(ModerationCaseNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleModerationCaseNotFound(
            ModerationCaseNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "MODERATION_CASE_NOT_FOUND",
                        "Moderation case was not found.",
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
