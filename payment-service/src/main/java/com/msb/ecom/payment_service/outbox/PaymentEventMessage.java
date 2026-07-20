package com.msb.ecom.payment_service.outbox;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentEventMessage(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        String paymentIntentId,
        String checkoutId,
        String partitionKey,
        Payload payload
) {
    public record Payload(
            String status,
            BigDecimal amount,
            String currency,
            String providerEventId
    ) {
    }
}
