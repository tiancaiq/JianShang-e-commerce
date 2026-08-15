package com.msb.ecom.order_service.controller;

import com.msb.ecom.order_service.dto.AddCartItemRequest;
import com.msb.ecom.order_service.dto.CartResponse;
import com.msb.ecom.order_service.dto.CartValidationResponse;
import com.msb.ecom.order_service.dto.UpdateCartItemRequest;
import com.msb.ecom.order_service.service.CartService;
import com.msb.ecom.order_service.service.CartValidationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final CartService cartService;
    private final CartValidationService cartValidationService;

    public CartController(CartService cartService, CartValidationService cartValidationService) {
        this.cartService = cartService;
        this.cartValidationService = cartValidationService;
    }

    @GetMapping
    public CartResponse get() {
        return cartService.get();
    }

    @PostMapping("/items")
    public CartResponse add(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AddCartItemRequest request) {
        return cartService.add(request.listingId(), request.quantity(), ifMatch, idempotencyKey);
    }

    @PatchMapping("/items/{listingId}")
    public CartResponse update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable String listingId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.update(listingId, request.quantity(), ifMatch, idempotencyKey);
    }

    @DeleteMapping("/items/{listingId}")
    public CartResponse remove(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable String listingId) {
        return cartService.remove(listingId, ifMatch, idempotencyKey);
    }

    @DeleteMapping
    public CartResponse clear(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return cartService.clear(ifMatch, idempotencyKey);
    }

    @PostMapping("/validate")
    public CartValidationResponse validate() {
        return cartValidationService.validate();
    }
}
