package com.msb.ecom.auth_service.governance;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = AdminGovernanceController.class)
public class GovernanceExceptionHandler {
    @ExceptionHandler(GovernanceException.class)
    ResponseEntity<ApiErrorEnvelope> governance(GovernanceException value, HttpServletRequest request) {
        return ResponseEntity.status(value.status()).body(new ApiErrorEnvelope(new ApiError(
                value.code(), value.getMessage(), List.of(), CorrelationIdFilter.current(request))));
    }
}
