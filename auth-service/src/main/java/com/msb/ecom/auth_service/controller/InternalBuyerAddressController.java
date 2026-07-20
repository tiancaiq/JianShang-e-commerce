package com.msb.ecom.auth_service.controller;

import com.msb.ecom.auth_service.dto.CheckoutAddressResolutionRequest;
import com.msb.ecom.auth_service.dto.CheckoutBuyerResolutionRequest;
import com.msb.ecom.auth_service.dto.CheckoutBuyerResolutionResponse;
import com.msb.ecom.auth_service.dto.InternalBuyerAddressResponse;
import com.msb.ecom.auth_service.service.AddressBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
public class InternalBuyerAddressController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final AddressBookService addressBookService;

    @GetMapping("/{buyerId}/addresses/{addressId}")
    public InternalBuyerAddressResponse resolve(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String buyerId,
            @PathVariable String addressId) {
        return addressBookService.resolveInternal(internalToken, buyerId, addressId);
    }

    @PostMapping("/checkout-address-resolution")
    public InternalBuyerAddressResponse resolveForCheckout(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody CheckoutAddressResolutionRequest request) {
        return addressBookService.resolveCheckoutAddress(
                internalToken,
                request == null ? null : request.subject(),
                request == null ? null : request.addressId());
    }

    @PostMapping("/checkout-buyer-resolution")
    public CheckoutBuyerResolutionResponse resolveBuyerForCheckout(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestBody CheckoutBuyerResolutionRequest request) {
        return new CheckoutBuyerResolutionResponse(addressBookService.resolveCheckoutBuyer(
                internalToken,
                request == null ? null : request.subject()));
    }
}
