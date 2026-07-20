package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.common.web.error.FieldError;
import com.msb.ecom.order_service.model.CheckoutException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = CheckoutController.class)
public class CheckoutExceptionHandler {

    @ExceptionHandler(CheckoutException.class)
    public ResponseEntity<ApiErrorEnvelope> handle(
            CheckoutException exception,
            HttpServletRequest request) {
        List<FieldError> details = exception.checkoutId() == null
                ? List.of()
                : List.of(new FieldError("checkoutId", exception.checkoutId()));
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiError(
                exception.code(),
                exception.getMessage(),
                details,
                CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiErrorEnvelope> invalidRequest(
            Exception exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorEnvelope(new ApiError(
                "CHECKOUT_INVALID_REQUEST",
                "Checkout request is invalid.",
                List.of(),
                CorrelationIdFilter.current(request))));
    }
}
