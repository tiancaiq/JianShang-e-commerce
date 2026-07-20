package com.msb.ecom.auth_service.dto;

public record InternalBuyerAddressResponse(
        String id,
        String buyerId,
        String label,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String city,
        String region,
        String postalCode,
        String countryCode,
        long version
) {
}
