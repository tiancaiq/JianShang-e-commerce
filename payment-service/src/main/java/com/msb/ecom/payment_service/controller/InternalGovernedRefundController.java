package com.msb.ecom.payment_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundExecution;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundRequest;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.service.AdminFinanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/admin/payments")
public class InternalGovernedRefundController {
    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";
    private final AdminFinanceService service;
    private final byte[] expectedToken;

    public InternalGovernedRefundController(AdminFinanceService service,
            @Value("${commerce.internal-service-token}") String token) {
        this.service = service;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{paymentId}/refund")
    public RefundExecution execute(@PathVariable String paymentId,
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String suppliedToken,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody RefundRequest request, HttpServletRequest servlet) {
        requireInternal(suppliedToken);
        RefundRequest body = new RefundRequest(request.refundType(), request.amount(), request.currency(),
                request.reasonCode(), request.reason(), request.disputeId(), request.expectedPaymentVersion(), key);
        return service.execute(paymentId, body, CorrelationIdFilter.current(servlet));
    }

    private void requireInternal(String supplied) {
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (expectedToken.length == 0 || !MessageDigest.isEqual(expectedToken, actual)) {
            throw new PaymentIntentException(HttpStatus.FORBIDDEN,
                    "PAYMENT_INTERNAL_AUTH_REQUIRED",
                    "Internal governance execution authentication is required.");
        }
    }
}
