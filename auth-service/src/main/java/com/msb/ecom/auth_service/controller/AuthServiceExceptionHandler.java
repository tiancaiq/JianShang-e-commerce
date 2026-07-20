package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.service.BusinessApplicationConflictException;
import com.msb.ecom.auth_service.service.BusinessApplicationForbiddenException;
import com.msb.ecom.auth_service.service.BusinessApplicationNotFoundException;
import com.msb.ecom.auth_service.service.BusinessApplicationVersionConflictException;
import com.msb.ecom.auth_service.service.BusinessMembershipNotFoundException;
import com.msb.ecom.auth_service.service.BusinessStoreForbiddenException;
import com.msb.ecom.auth_service.service.BusinessStoreNotFoundException;
import com.msb.ecom.auth_service.service.BusinessStoreSlugConflictException;
import com.msb.ecom.auth_service.service.BusinessStoreVersionConflictException;
import com.msb.ecom.auth_service.service.AvatarNotFoundException;
import com.msb.ecom.auth_service.service.AvatarStorageException;
import com.msb.ecom.auth_service.service.IndividualSellerAlreadyActiveException;
import com.msb.ecom.auth_service.service.IndividualSellerProfileNotFoundException;
import com.msb.ecom.auth_service.service.InternalCommerceAuthorizationException;
import com.msb.ecom.auth_service.service.ProfileVersionConflictException;
import com.msb.ecom.auth_service.service.AddressBookLimitReachedException;
import com.msb.ecom.auth_service.service.AddressNotFoundException;
import com.msb.ecom.auth_service.service.AddressValidationException;
import com.msb.ecom.auth_service.service.AddressVersionConflictException;
import com.msb.ecom.auth_service.service.BuyerAddressNotFoundException;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.common.web.error.FieldError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
@Slf4j
public class AuthServiceExceptionHandler {

    @ExceptionHandler(AddressValidationException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAddressValidation(
            AddressValidationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorEnvelope(new ApiError(
                        "ADDRESS_INVALID",
                        "One or more address fields are invalid.",
                        List.of(new FieldError(exception.field(), exception.fieldCode())),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(AddressNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAddressNotFound(
            AddressNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "ADDRESS_NOT_FOUND",
                        "Address was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BuyerAddressNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBuyerAddressNotFound(
            BuyerAddressNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "BUYER_ADDRESS_NOT_FOUND",
                        "Buyer address was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(AddressVersionConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAddressVersionConflict(
            AddressVersionConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "ADDRESS_VERSION_CONFLICT",
                        "The address changed. Refresh and try again.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(AddressBookLimitReachedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAddressBookLimitReached(
            AddressBookLimitReachedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "ADDRESS_BOOK_LIMIT_REACHED",
                        "The address book already contains 20 addresses.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(ProfileVersionConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleProfileVersionConflict(
            ProfileVersionConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "VERSION_CONFLICT",
                        "The profile changed. Refresh and try again.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(AvatarNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAvatarNotFound(
            AvatarNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "AVATAR_NOT_FOUND",
                        "Avatar image was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(AvatarStorageException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAvatarStorage(
            AvatarStorageException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Avatar storage request failed path={} correlationId={}",
                request.getRequestURI(), correlationId, exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorEnvelope(new ApiError(
                        "AVATAR_STORAGE_UNAVAILABLE",
                        "Avatar storage is temporarily unavailable.",
                        List.of(),
                        correlationId)));
    }

    @ExceptionHandler(IndividualSellerAlreadyActiveException.class)
    public ResponseEntity<ApiErrorEnvelope> handleIndividualSellerAlreadyActive(
            IndividualSellerAlreadyActiveException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "INDIVIDUAL_SELLER_ALREADY_ACTIVE",
                        "Individual seller profile already exists.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(IndividualSellerProfileNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleIndividualSellerProfileNotFound(
            IndividualSellerProfileNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "INDIVIDUAL_SELLER_NOT_FOUND",
                        "Individual seller profile was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BusinessApplicationConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessApplicationConflict(
            BusinessApplicationConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "BUSINESS_APPLICATION_CONFLICT",
                        exception.getMessage(),
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BusinessApplicationNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessApplicationNotFound(
            BusinessApplicationNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "BUSINESS_APPLICATION_NOT_FOUND",
                        "Business application was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BusinessApplicationForbiddenException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessApplicationForbidden(
            BusinessApplicationForbiddenException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Denied auth-service business action path={} correlationId={} reason=forbidden",
                request.getRequestURI(), correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorEnvelope(new ApiError(
                        "FORBIDDEN",
                        "You are not allowed to perform this business application action.",
                        List.of(),
                        correlationId)));
    }

    @ExceptionHandler(BusinessApplicationVersionConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessApplicationVersionConflict(
            BusinessApplicationVersionConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "VERSION_CONFLICT",
                        "The business application changed. Refresh and try again.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BusinessMembershipNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessMembershipNotFound(
            BusinessMembershipNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "BUSINESS_MEMBERSHIP_NOT_FOUND",
                        "Business membership was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BusinessStoreNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessStoreNotFound(
            BusinessStoreNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorEnvelope(new ApiError(
                        "BUSINESS_STORE_NOT_FOUND",
                        "Business store was not found.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BusinessStoreForbiddenException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessStoreForbidden(
            BusinessStoreForbiddenException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Denied auth-service business store action path={} correlationId={} reason=forbidden",
                request.getRequestURI(), correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorEnvelope(new ApiError(
                        "FORBIDDEN",
                        "You are not allowed to perform this business store action.",
                        List.of(),
                        correlationId)));
    }

    @ExceptionHandler(BusinessStoreVersionConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessStoreVersionConflict(
            BusinessStoreVersionConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "VERSION_CONFLICT",
                        "The business store changed. Refresh and try again.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(BusinessStoreSlugConflictException.class)
    public ResponseEntity<ApiErrorEnvelope> handleBusinessStoreSlugConflict(
            BusinessStoreSlugConflictException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorEnvelope(new ApiError(
                        "BUSINESS_STORE_SLUG_CONFLICT",
                        "Store slug is already in use.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(InternalCommerceAuthorizationException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInternalCommerceAuthorization(
            InternalCommerceAuthorizationException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Denied internal commerce request path={} correlationId={}",
                request.getRequestURI(), correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorEnvelope(new ApiError(
                        "INTERNAL_COMMERCE_AUTH_REQUIRED",
                        "Internal commerce service authentication is required.",
                        List.of(),
                        correlationId)));
    }
}
