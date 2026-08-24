package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.order_service.model.AdminOrderException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = AdminOrderController.class)
public class AdminOrderExceptionHandler {
    @ExceptionHandler(AdminOrderException.class)
    public ResponseEntity<ApiErrorEnvelope> handle(AdminOrderException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiError(
                exception.code(), exception.getMessage(), List.of(), CorrelationIdFilter.current(request))));
    }
}
