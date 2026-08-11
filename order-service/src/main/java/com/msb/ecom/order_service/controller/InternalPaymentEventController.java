package com.msb.ecom.order_service.controller;

import com.msb.ecom.order_service.model.OrderConfirmationResult;
import com.msb.ecom.order_service.service.InternalOrderEventAuthenticator;
import com.msb.ecom.order_service.service.PaymentEventEnvelopeParser;
import com.msb.ecom.order_service.service.PaymentSucceededOrderConfirmationHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/events/payments")
public class InternalPaymentEventController {

    private final InternalOrderEventAuthenticator authenticator;
    private final PaymentEventEnvelopeParser parser;
    private final PaymentSucceededOrderConfirmationHandler handler;

    public InternalPaymentEventController(
            InternalOrderEventAuthenticator authenticator,
            PaymentEventEnvelopeParser parser,
            PaymentSucceededOrderConfirmationHandler handler) {
        this.authenticator = authenticator;
        this.parser = parser;
        this.handler = handler;
    }

    @PostMapping
    public ResponseEntity<OrderConfirmationResult> consume(
            @RequestHeader(name = "X-Internal-Service-Token", required = false) String token,
            @RequestBody byte[] body) {
        authenticator.requireAuthenticated(token);
        OrderConfirmationResult result = handler.handle(parser.parse(body));
        HttpStatus status = switch (result.outcome()) {
            case CONFIRMED, REPLAYED -> HttpStatus.OK;
            case IN_PROGRESS, RETRY_REQUIRED -> HttpStatus.SERVICE_UNAVAILABLE;
            case REJECTED -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(result);
    }
}
