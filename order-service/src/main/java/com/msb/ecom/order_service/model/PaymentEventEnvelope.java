package com.msb.ecom.order_service.model;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentEventEnvelope(
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
