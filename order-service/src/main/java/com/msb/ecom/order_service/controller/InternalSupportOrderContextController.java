package com.msb.ecom.order_service.controller;

import com.msb.ecom.order_service.repository.AdminOrderRepository;
import com.msb.ecom.order_service.repository.OrderDisputeRepository;
import com.msb.ecom.order_service.service.InternalOrderEventAuthenticator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/support")
public class InternalSupportOrderContextController {
    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";

    private final InternalOrderEventAuthenticator authenticator;
    private final AdminOrderRepository orders;
    private final OrderDisputeRepository disputes;

    public InternalSupportOrderContextController(InternalOrderEventAuthenticator authenticator,
            AdminOrderRepository orders, OrderDisputeRepository disputes) {
        this.authenticator = authenticator;
        this.orders = orders;
        this.disputes = disputes;
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> order(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String token,
            @PathVariable String orderId,
            @RequestParam(required = false) String requesterUserId) {
        authenticator.requireAuthenticated(token);
        return orders.detail(orderId)
                .filter(order -> requesterUserId == null || requesterUserId.equals(order.buyerId()))
                .map(order -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("safeLabel", "Order " + order.orderNumber());
                    result.put("orderId", order.orderId());
                    result.put("orderNumber", order.orderNumber());
                    result.put("status", order.status());
                    result.put("paymentStatus", order.paymentStatus());
                    result.put("amount", order.total());
                    result.put("currency", order.currency());
                    result.put("paymentId", order.paymentIntentId());
                    result.put("businessIds", orders.groups(orderId).stream()
                            .map(AdminOrderRepository.GroupRow::businessId).distinct().toList());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/disputes/{disputeId}")
    public ResponseEntity<Map<String, Object>> dispute(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String token,
            @PathVariable String disputeId,
            @RequestParam(required = false) String requesterUserId) {
        authenticator.requireAuthenticated(token);
        return disputes.find(disputeId, false)
                .filter(dispute -> requesterUserId == null || requesterUserId.equals(dispute.buyerId()))
                .map(dispute -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("safeLabel", "Dispute " + dispute.id());
                    result.put("disputeId", dispute.id());
                    result.put("orderId", dispute.orderId());
                    result.put("businessId", dispute.businessId());
                    result.put("status", dispute.status().name());
                    result.put("priority", dispute.priority().name());
                    result.put("createdAt", dispute.createdAt());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
