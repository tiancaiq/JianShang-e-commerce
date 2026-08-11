package com.msb.ecom.order_service.outbox;

import java.time.Instant;
import java.util.List;

public record CommerceNotificationEvent(
        String eventId, String eventType, int eventVersion, Instant occurredAt,
        String producer, String aggregateType, String aggregateId,
        String correlationId, String causationId, List<Target> targets) {
    public record Target(
            String scopeType, String scopeId, String notificationType,
            String orderId, String businessId, String businessOrderId,
            String storeDisplayName) {}
}
