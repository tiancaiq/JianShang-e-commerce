package com.msb.ecom.payment_service.controller;

import com.msb.ecom.payment_service.repository.AdminFinanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/support")
public class InternalSupportFinanceContextController {
    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";
    private final AdminFinanceRepository finance;
    private final byte[] expectedToken;

    public InternalSupportFinanceContextController(AdminFinanceRepository finance,
            @Value("${commerce.internal-service-token:}") String token) {
        this.finance = finance;
        this.expectedToken = digest(token);
    }

    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<Map<String, Object>> payment(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String token,
            @PathVariable String paymentId,
            @RequestParam(required = false) String requesterUserId) {
        requireAuthenticated(token);
        return finance.payment(paymentId, false)
                .filter(payment -> requesterUserId == null || requesterUserId.equals(payment.buyerId()))
                .map(payment -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("safeLabel", "Payment " + payment.id());
                    result.put("paymentId", payment.id());
                    result.put("status", payment.status());
                    result.put("amount", payment.amount());
                    result.put("currency", payment.currency());
                    result.put("refundableAmount", payment.refundable());
                    result.put("refundCount", payment.refundCount());
                    result.put("businessIds", finance.businessIds(payment.id()));
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/refunds/{refundId}")
    public ResponseEntity<Map<String, Object>> refund(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String token,
            @PathVariable String refundId,
            @RequestParam(required = false) String requesterUserId) {
        requireAuthenticated(token);
        return finance.refund(refundId).flatMap(refund -> finance.payment(refund.paymentId(), false)
                        .filter(payment -> requesterUserId == null || requesterUserId.equals(payment.buyerId()))
                        .map(payment -> {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("safeLabel", "Refund " + refund.id());
                            result.put("refundId", refund.id());
                            result.put("paymentId", refund.paymentId());
                            result.put("orderId", refund.orderId());
                            result.put("status", refund.status());
                            result.put("amount", refund.amount());
                            result.put("currency", refund.currency());
                            result.put("reconciliation", refund.reconciliation());
                            return ResponseEntity.ok(result);
                        }))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void requireAuthenticated(String supplied) {
        if (expectedToken.length == 0 || !MessageDigest.isEqual(expectedToken, digest(supplied == null ? "" : supplied))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal support context authentication is required.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
