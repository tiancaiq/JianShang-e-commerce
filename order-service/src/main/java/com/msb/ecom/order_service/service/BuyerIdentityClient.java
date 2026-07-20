package com.msb.ecom.order_service.service;

public interface BuyerIdentityClient {

    BuyerAddress resolveAddress(String subject, String addressId);

    String resolveBuyer(String subject);

    record BuyerAddress(
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
}
