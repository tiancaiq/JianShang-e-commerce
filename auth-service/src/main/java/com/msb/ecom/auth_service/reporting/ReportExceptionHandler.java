package com.msb.ecom.auth_service.reporting;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = {ReportController.class, AdminReportController.class})
public class ReportExceptionHandler {
    @ExceptionHandler(ReportException.class)
    public ResponseEntity<ApiErrorEnvelope> report(ReportException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiError(
                exception.code(), exception.getMessage(), List.of(), CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorEnvelope> invalid(IllegalArgumentException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiErrorEnvelope(new ApiError(
                "REPORT_INVALID_REQUEST", exception.getMessage(), List.of(), CorrelationIdFilter.current(request))));
    }
}
