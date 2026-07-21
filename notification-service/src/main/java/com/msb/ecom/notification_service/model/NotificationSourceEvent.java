package com.msb.ecom.notification_service.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record NotificationSourceEvent(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        String partitionKey,
        String correlationId,
        String causationId,
        JsonNode payload,
        JsonNode canonicalTree
) {

    public boolean isSupportedOrderConfirmed() {
        return "order.confirmed".equals(eventType) && eventVersion == 2;
    }
}
