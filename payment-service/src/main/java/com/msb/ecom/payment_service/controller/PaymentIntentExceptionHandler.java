package com.msb.ecom.payment_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = {
        InternalPaymentIntentController.class,
        PaymentWebhookController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentIntentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentExceptionHandler.class);

    @ExceptionHandler(PaymentIntentException.class)
    public ResponseEntity<ApiErrorEnvelope> handle(
            PaymentIntentException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiErrorEnvelope(new ApiError(
                exception.code(),
                exception.getMessage(),
                List.of(),
                CorrelationIdFilter.current(request))));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorEnvelope> handleUnexpectedState(
            IllegalStateException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.error(
                "Unexpected payment state failure correlationId={} exceptionType={}",
                correlationId,
                exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorEnvelope(new ApiError(
                        "PAYMENT_INTERNAL_ERROR",
                        "An unexpected payment error occurred.",
                        List.of(),
                        correlationId)));
    }
}
