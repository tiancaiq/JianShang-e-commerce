package com.msb.ecom.auth_service.appeals;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = {AppealController.class, AdminAppealController.class})
public class AppealExceptionHandler {
    @ExceptionHandler(AppealException.class)
    public ResponseEntity<ApiErrorEnvelope> appeal(AppealException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiError(
                exception.code(), exception.getMessage(), List.of(), CorrelationIdFilter.current(request))));
    }
}
