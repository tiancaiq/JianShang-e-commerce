package com.msb.ecom.order_service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CheckoutPaymentBinding(
        String paymentIntentId,
        String checkoutId,
        long checkoutVersion,
        String checkoutSnapshotHash,
        String buyerId,
        List<String> businessIds,
        BigDecimal amount,
        String currency,
        Instant expiresAt,
        Instant createdAt
) {
    public CheckoutPaymentBinding {
        businessIds = List.copyOf(businessIds);
    }
}
