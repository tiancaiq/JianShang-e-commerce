package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.CheckoutException;
import org.springframework.http.HttpStatus;

import java.util.Set;

public interface BuyerIdentityClient {

    BuyerAddress resolveAddress(String subject, String addressId);

    String resolveBuyer(String subject);

    void requireCapability(String userId, String scope);

    default void requireBusinessCapabilities(Set<String> businessIds, String scope) {
        throw new CheckoutException(HttpStatus.SERVICE_UNAVAILABLE, "ENFORCEMENT_DECISION_UNAVAILABLE",
                "Business marketplace capability validation is temporarily unavailable.");
    }

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
