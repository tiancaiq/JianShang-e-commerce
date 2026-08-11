package com.msb.ecom.payment_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.payment_service.dto.PaymentWebhookResponse;
import com.msb.ecom.payment_service.service.PaymentWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/payments")
public class PaymentWebhookController {

    private final PaymentWebhookService service;

    public PaymentWebhookController(PaymentWebhookService service) {
        this.service = service;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<PaymentWebhookResponse> process(
            @PathVariable String provider,
            @RequestHeader HttpHeaders headers,
            @RequestBody byte[] rawBody,
            HttpServletRequest servletRequest) {
        PaymentWebhookResponse response = service.process(
                provider,
                headers,
                rawBody,
                CorrelationIdFilter.current(servletRequest));
        HttpStatus status = switch (response.outcome()) {
            case "UNKNOWN_INTENT", "UNKNOWN_REFERENCE" -> HttpStatus.NOT_FOUND;
            case "REJECTED_ILLEGAL_TRANSITION" -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping
    public ResponseEntity<PaymentWebhookResponse> processLegacyFake(
            @RequestHeader HttpHeaders headers,
            @RequestBody byte[] rawBody,
            HttpServletRequest servletRequest) {
        return process("FAKE_LOCAL_DEMO_V1", headers, rawBody, servletRequest);
    }
}
