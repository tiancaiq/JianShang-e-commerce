package com.msb.ecom.order_service.controller;

import com.msb.ecom.order_service.dto.AdminFinanceOrderContext;
import com.msb.ecom.order_service.service.AdminFinanceOrderContextService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/internal/admin-finance")
public class InternalAdminFinanceContextController {
    private final AdminFinanceOrderContextService service;
    public InternalAdminFinanceContextController(AdminFinanceOrderContextService service) { this.service = service; }

    @PostMapping("/payment-contexts")
    public List<AdminFinanceOrderContext> contexts(
            @RequestHeader(name="X-Internal-Service-Token",required=false) String token,
            @RequestBody(required=false) ContextRequest request) {
        return service.contexts(token, request == null ? null : request.paymentIntentIds());
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<AdminFinanceOrderContext> order(
            @RequestHeader(name="X-Internal-Service-Token",required=false) String token,
            @PathVariable String orderId) {
        return service.order(token,orderId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record ContextRequest(List<String> paymentIntentIds) { }
}
