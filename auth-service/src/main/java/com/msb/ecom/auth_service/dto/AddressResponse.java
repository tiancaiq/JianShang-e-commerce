package com.msb.ecom.auth_service.dto;

import com.msb.ecom.auth_service.model.Address;

import java.time.Instant;

public record AddressResponse(
        String id,
        String label,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String city,
        String region,
        String postalCode,
        String countryCode,
        boolean isDefault,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static AddressResponse from(Address address) {
        return new AddressResponse(
                address.getId(),
                address.getLabel(),
                address.getRecipientName(),
                address.getPhone(),
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getRegion(),
                address.getPostalCode(),
                address.getCountryCode(),
                address.isDefaultAddress(),
                address.getVersion(),
                address.getCreatedAt(),
                address.getUpdatedAt());
    }
}
