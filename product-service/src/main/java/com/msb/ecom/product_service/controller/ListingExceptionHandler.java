package com.msb.ecom.product_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.product_service.model.BusinessSkuConflictException;
import com.msb.ecom.product_service.model.CategoryNotFoundException;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.model.ListingMediaAccessDeniedException;
import com.msb.ecom.product_service.model.ListingMediaNotFoundException;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import com.msb.ecom.product_service.model.ListingVersionConflictException;
import com.msb.ecom.product_service.model.ModerationCaseNotFoundException;
import com.msb.ecom.product_service.model.ModerationCaseVersionConflictException;
import com.msb.ecom.product_service.search.ListingSearchUnavailableException;
import com.msb.ecom.product_service.knowledge.ListingKnowledgeSourceNotFoundException;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceCategoryInactiveException;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceNotFoundException;
import com.msb.ecom.product_service.knowledge.CategoryGuidanceVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

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

    @ExceptionHandler(ListingKnowledgeSourceNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingKnowledgeSourceNotFound(
            ListingKnowledgeSourceNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_KNOWLEDGE_SOURCE_NOT_FOUND",
                        "Listing knowledge source version was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(CategoryGuidanceNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleCategoryGuidanceNotFound(
            CategoryGuidanceNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "CATEGORY_GUIDANCE_NOT_FOUND",
                        "Category guidance source version was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(CategoryGuidanceVersionConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleCategoryGuidanceVersionConflict(
            CategoryGuidanceVersionConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "CATEGORY_GUIDANCE_VERSION_CONFLICT",
                        "Category guidance was changed by another request.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(CategoryGuidanceCategoryInactiveException.class)
    public ResponseEntity<ApiErrorEnvelope> handleCategoryGuidanceCategoryInactive(
            CategoryGuidanceCategoryInactiveException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "CATEGORY_GUIDANCE_CATEGORY_INACTIVE",
                        "Guidance cannot be published for an inactive category.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
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

    @ExceptionHandler(ListingSearchUnavailableException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingSearchUnavailable(
            ListingSearchUnavailableException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Listing search unavailable path={} correlationId={} reason={}",
                request.getRequestURI(), correlationId, exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_SEARCH_UNAVAILABLE",
                        "Listing search is temporarily unavailable.",
                        List.of(),
                        correlationId)));
    }

    @ExceptionHandler(BusinessSkuConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessSkuConflict(
            BusinessSkuConflictException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Rejected duplicate business SKU path={} correlationId={}",
                request.getRequestURI(), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "BUSINESS_SKU_CONFLICT",
                        exception.getMessage(),
                        List.of(),
                        correlationId)));
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorEnvelope> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorEnvelope(new ApiError(
                        "LISTING_INVALID_REQUEST",
                        "Request body is invalid or contains unsupported fields.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }
}
