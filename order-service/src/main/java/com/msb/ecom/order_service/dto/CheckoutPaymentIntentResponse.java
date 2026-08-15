package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckoutPaymentIntentResponse(
        String id,
        String checkoutId,
        String status,
        long version,
        BigDecimal amount,
        String currency,
        Instant expiresAt,
        ProviderAction action,
        SafeError error
) {
    public record ProviderAction(String type, String reference, String publicKey, String returnUrl) {
        public ProviderAction(String type, String reference) {
            this(type, reference, null, null);
        }
    }

    public record SafeError(String code, String message) {
    }
}
