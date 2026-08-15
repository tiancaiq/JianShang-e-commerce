package com.msb.ecom.auth_service.dto;

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
}
