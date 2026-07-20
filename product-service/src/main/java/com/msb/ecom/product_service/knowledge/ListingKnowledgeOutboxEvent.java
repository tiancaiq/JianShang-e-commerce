package com.msb.ecom.product_service.knowledge;

import java.time.Instant;

public record ListingKnowledgeOutboxEvent(
        String eventId,
        String topic,
        String messageKey,
        String aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        String producer,
        Instant occurredAt,
        String correlationId,
        String payloadJson,
        int retryCount,
        Instant createdAt
) {
}
