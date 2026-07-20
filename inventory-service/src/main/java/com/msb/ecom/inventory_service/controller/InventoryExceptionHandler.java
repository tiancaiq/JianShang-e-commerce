package com.msb.ecom.inventory_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.inventory_service.model.InventoryException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class InventoryExceptionHandler {

    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<ApiErrorEnvelope> handle(
            InventoryException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(new ApiErrorEnvelope(new ApiError(
                        exception.code(),
                        exception.getMessage(),
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }
}
