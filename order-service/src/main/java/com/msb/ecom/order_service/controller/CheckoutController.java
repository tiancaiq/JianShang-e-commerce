package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.order_service.dto.CheckoutPaymentIntentResponse;
import com.msb.ecom.order_service.dto.CheckoutResponse;
import com.msb.ecom.order_service.dto.CheckoutOrderResolutionResponse;
import com.msb.ecom.order_service.dto.DemoPaymentCompletionResponse;
import com.msb.ecom.order_service.dto.CreateCheckoutRequest;
import com.msb.ecom.order_service.service.CheckoutPaymentService;
import com.msb.ecom.order_service.service.CheckoutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkouts")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final CheckoutPaymentService checkoutPaymentService;

    public CheckoutController(
            CheckoutService checkoutService,
            CheckoutPaymentService checkoutPaymentService) {
        this.checkoutService = checkoutService;
        this.checkoutPaymentService = checkoutPaymentService;
    }

    @PostMapping
    public ResponseEntity<CheckoutResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCheckoutRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(checkoutService.create(
                idempotencyKey,
                request,
                CorrelationIdFilter.current(servletRequest)));
    }

    @GetMapping("/{checkoutId}")
    public CheckoutResponse get(@PathVariable String checkoutId) {
        return checkoutService.get(checkoutId);
    }

    @PostMapping("/{checkoutId}/cancel")
    public CheckoutResponse cancel(
            @PathVariable String checkoutId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return checkoutService.cancel(
                checkoutId,
                idempotencyKey,
                CorrelationIdFilter.current(servletRequest));
    }

    @PostMapping("/{checkoutId}/payment-intent")
    public CheckoutPaymentIntentResponse createPaymentIntent(
            @PathVariable String checkoutId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        return checkoutPaymentService.create(
                checkoutId,
                idempotencyKey,
                CorrelationIdFilter.current(servletRequest));
    }

    @PostMapping("/{checkoutId}/complete-demo-payment")
    public DemoPaymentCompletionResponse completeDemoPayment(
            @PathVariable String checkoutId,
            HttpServletRequest servletRequest) {
        return checkoutPaymentService.completeDemo(
                checkoutId,
                CorrelationIdFilter.current(servletRequest));
    }

    @GetMapping("/{checkoutId}/confirmed-order")
    public ResponseEntity<CheckoutOrderResolutionResponse> confirmedOrder(
            @PathVariable String checkoutId) {
        CheckoutOrderResolutionResponse response = checkoutPaymentService.confirmedOrder(checkoutId);
        return ResponseEntity.status(response.confirmed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(response);
    }
}
