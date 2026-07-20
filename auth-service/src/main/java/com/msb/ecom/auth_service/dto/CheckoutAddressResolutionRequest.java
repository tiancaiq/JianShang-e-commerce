package com.msb.ecom.auth_service.dto;

public record CheckoutAddressResolutionRequest(
        String subject,
        String addressId
) {
}
