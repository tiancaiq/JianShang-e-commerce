package com.msb.ecom.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PaymentIntentResponse(
        String id,
        String checkoutId,
        long checkoutVersion,
        String buyerId,
        List<String> businessIds,
        BigDecimal amount,
        String currency,
        String provider,
        String providerReference,
        String status,
        long version,
        Instant expiresAt,
        ProviderAction providerAction,
        SafeProviderError error,
        Instant createdAt,
        Instant updatedAt
) {
    public record ProviderAction(String type, String reference, String publicKey, String returnUrl) {
        public ProviderAction(String type, String reference) {
            this(type, reference, null, null);
        }
    }

    public record SafeProviderError(String code, String message) {
    }
}
