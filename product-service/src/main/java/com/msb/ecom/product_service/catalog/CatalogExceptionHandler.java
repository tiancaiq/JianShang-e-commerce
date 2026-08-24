package com.msb.ecom.product_service.catalog;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import com.msb.ecom.product_service.controller.ListingController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = {AdminCatalogController.class, ListingController.class})
public class CatalogExceptionHandler {
    @ExceptionHandler(CatalogException.class)
    ResponseEntity<ApiErrorEnvelope> catalog(CatalogException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiError(
                exception.code(), exception.getMessage(), List.of(), CorrelationIdFilter.current(request))));
    }
}
