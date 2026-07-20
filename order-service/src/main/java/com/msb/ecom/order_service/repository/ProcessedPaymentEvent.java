package com.msb.ecom.order_service.repository;

import java.time.Instant;

public record ProcessedPaymentEvent(
        String consumerName,
        String eventId,
        String payloadHash,
        String paymentIntentId,
        String checkoutId,
        String state,
        String outcome,
        String safeErrorCode,
        String orderId,
        String claimToken,
        Instant claimExpiresAt
) {
}
