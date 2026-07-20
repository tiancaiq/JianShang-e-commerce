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
    public CartResponse add(@Valid @RequestBody AddCartItemRequest request) {
        return cartService.add(request.listingId(), request.quantity());
    }

    @PatchMapping("/items/{listingId}")
    public CartResponse update(
            @PathVariable String listingId,
            @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.update(listingId, request.quantity());
    }

    @DeleteMapping("/items/{listingId}")
    public CartResponse remove(@PathVariable String listingId) {
        return cartService.remove(listingId);
    }

    @DeleteMapping
    public CartResponse clear() {
        return cartService.clear();
    }

    @PostMapping("/validate")
    public CartValidationResponse validate() {
        return cartValidationService.validate();
    }
}
