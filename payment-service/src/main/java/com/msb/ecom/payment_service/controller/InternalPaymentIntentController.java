package com.msb.ecom.payment_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.payment_service.dto.CreatePaymentIntentRequest;
import com.msb.ecom.payment_service.dto.PaymentIntentResponse;
import com.msb.ecom.payment_service.service.PaymentIntentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/internal/payment-intents")
public class InternalPaymentIntentController {

    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";

    private final PaymentIntentService service;

    public InternalPaymentIntentController(PaymentIntentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentIntentResponse create(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String internalToken,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentIntentRequest request,
            HttpServletRequest servletRequest) {
        return service.create(
                internalToken,
                idempotencyKey,
                request,
                CorrelationIdFilter.current(servletRequest));
    }

    @GetMapping("/{paymentIntentId}")
    public PaymentIntentResponse get(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String internalToken,
            @PathVariable
            @Pattern(regexp = "[0-7][0-9A-HJKMNP-TV-Z]{25}") String paymentIntentId,
            @RequestParam
            @Pattern(regexp = "[0-7][0-9A-HJKMNP-TV-Z]{25}") String buyerId) {
        return service.get(internalToken, paymentIntentId, buyerId);
    }
}
