package com.msb.ecom.payment_service.outbox;

import java.time.Instant;

public record PaymentOutboxRecord(
        String id,
        String aggregateType,
        String paymentIntentId,
        String eventType,
        int eventVersion,
        String producer,
        String payloadJson,
        String correlationId,
        String causationId,
        Instant occurredAt,
        Instant createdAt,
        int retryCount,
        int attemptCount
) {
}
