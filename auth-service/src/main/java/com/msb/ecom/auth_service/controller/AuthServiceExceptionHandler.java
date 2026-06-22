package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.service.BusinessApplicationConflictException;
import com.msb.ecom.auth_service.service.BusinessApplicationForbiddenException;
import com.msb.ecom.auth_service.service.BusinessApplicationNotFoundException;
import com.msb.ecom.auth_service.service.BusinessApplicationVersionConflictException;
import com.msb.ecom.auth_service.service.BusinessMembershipNotFoundException;
import com.msb.ecom.auth_service.service.IndividualSellerAlreadyActiveException;
import com.msb.ecom.auth_service.service.IndividualSellerProfileNotFoundException;
import com.msb.ecom.auth_service.service.ProfileVersionConflictException;
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
public class AuthServiceExceptionHandler {

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
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorEnvelope(new ApiError(
                        "FORBIDDEN",
                        "You are not allowed to perform this business application action.",
                        List.of(),
                        CorrelationIdFilter.current(request))));
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
}
