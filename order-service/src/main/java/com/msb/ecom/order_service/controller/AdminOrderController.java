package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancelRequest;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancellationPreview;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancellationResult;
import com.msb.ecom.order_service.dto.AdminOrderContracts.Detail;
import com.msb.ecom.order_service.dto.AdminOrderContracts.Page;
import com.msb.ecom.order_service.service.AdminOrderCancellationService;
import com.msb.ecom.order_service.service.AdminOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin/orders")
public class AdminOrderController {
    private final AdminOrderService orders;
    private final AdminOrderCancellationService cancellation;

    public AdminOrderController(AdminOrderService orders, AdminOrderCancellationService cancellation) {
        this.orders = orders;
        this.cancellation = cancellation;
    }

    @GetMapping
    public Page search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String buyerUserId,
            @RequestParam(required = false) String businessId,
            @RequestParam(required = false) String listingId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String fulfillmentStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return orders.search(q, buyerUserId, businessId, listingId, status, paymentStatus,
                fulfillmentStatus, createdFrom, createdTo, page, size, sort);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Detail> detail(@PathVariable String orderId) {
        Detail response = orders.detail(orderId);
        return ResponseEntity.ok().eTag(Long.toString(response.version()))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping("/{orderId}/cancel/dry-run")
    public ResponseEntity<CancellationPreview> preview(@PathVariable String orderId,
            @RequestBody(required = false) CancelRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(cancellation.preview(orderId, request));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<CancellationResult> cancel(@PathVariable String orderId,
            @RequestBody(required = false) CancelRequest request, HttpServletRequest servletRequest) {
        CancellationResult response = cancellation.execute(orderId, request,
                CorrelationIdFilter.current(servletRequest));
        return ResponseEntity.ok().eTag(Long.toString(response.version()))
                .cacheControl(CacheControl.noStore()).body(response);
    }
}
