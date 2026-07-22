package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.order_service.dto.OrderCancellationResponse;
import com.msb.ecom.order_service.service.OrderCancellationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/cancellation-requests")
public class OrderCancellationController {

    private final OrderCancellationService service;

    public OrderCancellationController(OrderCancellationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderCancellationResponse> request(
            @PathVariable String orderId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        boolean bodyPresent = request.getContentLengthLong() > 0
                || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
        OrderCancellationResponse response = service.request(
                orderId,
                ifMatch,
                idempotencyKey,
                bodyPresent,
                CorrelationIdFilter.current(request));
        return ResponseEntity.ok()
                .eTag(Long.toString(response.version()))
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
