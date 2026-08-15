package com.msb.ecom.product_service.dto;

import java.time.Instant;
import java.util.Map;

public record AdminTimelineEntryResponse(
        String eventId,
        Instant occurredAt,
        String eventType,
        String actorType,
        String actorId,
        String actorDisplay,
        String source,
        String targetType,
        String targetId,
        String moderationCaseId,
        String previousState,
        String newState,
        String reason,
        String correlationId,
        Map<String, String> metadata
) {
    public AdminTimelineEntryResponse withActorDisplay(String display) {
        return new AdminTimelineEntryResponse(
                eventId,
                occurredAt,
                eventType,
                actorType,
                actorId,
                display,
                source,
                targetType,
                targetId,
                moderationCaseId,
                previousState,
                newState,
                reason,
                correlationId,
                metadata);
    }
}
