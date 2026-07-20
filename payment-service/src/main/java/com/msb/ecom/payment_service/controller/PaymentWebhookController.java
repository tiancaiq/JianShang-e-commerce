package com.msb.ecom.payment_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.payment_service.dto.PaymentWebhookResponse;
import com.msb.ecom.payment_service.service.PaymentWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/payments")
public class PaymentWebhookController {

    private final PaymentWebhookService service;

    public PaymentWebhookController(PaymentWebhookService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentWebhookResponse> process(
            @RequestHeader(name = "X-MSB-Signature", required = false) String signature,
            @RequestBody byte[] rawBody,
            HttpServletRequest servletRequest) {
        PaymentWebhookResponse response = service.process(
                signature,
                rawBody,
                CorrelationIdFilter.current(servletRequest));
        HttpStatus status = switch (response.outcome()) {
            case "UNKNOWN_INTENT" -> HttpStatus.NOT_FOUND;
            case "REJECTED_ILLEGAL_TRANSITION" -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(response);
    }
}
