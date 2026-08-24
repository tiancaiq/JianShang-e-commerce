package com.msb.ecom.auth_service.analytics;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice(assignableTypes = AdminAnalyticsController.class)
public class AdminAnalyticsExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorEnvelope> invalid(IllegalArgumentException exception,
            HttpServletRequest request) {
        return badRequest(exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorEnvelope> typeMismatch(HttpServletRequest request) {
        return badRequest("An analytics range, metric, or granularity value is invalid.", request);
    }

    private ResponseEntity<ApiErrorEnvelope> badRequest(String message, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(new ApiErrorEnvelope(new ApiError(
                "ANALYTICS_REQUEST_INVALID", message, List.of(),
                CorrelationIdFilter.current(request))));
    }
}
