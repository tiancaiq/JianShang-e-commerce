package com.msb.ecom.auth_service.system;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = SystemOperationsController.class)
public class SystemOperationsExceptionHandler {
    @ExceptionHandler(SystemOperationsException.class)
    ResponseEntity<ApiErrorEnvelope> system(SystemOperationsException value,
            HttpServletRequest request) {
        return ResponseEntity.status(value.status()).body(new ApiErrorEnvelope(new ApiError(
                value.code(), value.getMessage(), List.of(), CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorEnvelope> invalid(IllegalArgumentException value,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiErrorEnvelope(new ApiError(
                "SYSTEM_REQUEST_INVALID", value.getMessage(), List.of(),
                CorrelationIdFilter.current(request))));
    }
}
