package com.msb.ecom.notification_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.notification_service.model.NotificationReadException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationReadExceptionHandler {

    @ExceptionHandler(NotificationReadException.class)
    public ResponseEntity<ApiErrorEnvelope> handle(
            NotificationReadException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiError(
                exception.code(),
                exception.getMessage(),
                List.of(),
                CorrelationIdFilter.current(request))));
    }
}
