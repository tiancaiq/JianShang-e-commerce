package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.order_service.model.OrderDisputeException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;

@RestControllerAdvice(assignableTypes={OrderDisputeController.class,AdminDisputeController.class})
public class OrderDisputeExceptionHandler {
    @ExceptionHandler(OrderDisputeException.class)
    public ResponseEntity<ApiErrorEnvelope> handle(OrderDisputeException e,HttpServletRequest request){return ResponseEntity.status(e.status()).body(new ApiErrorEnvelope(new ApiError(e.code(),e.getMessage(),List.of(),CorrelationIdFilter.current(request))));}
}
