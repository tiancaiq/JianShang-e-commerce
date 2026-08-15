package com.msb.ecom.product_service.repository;

import java.time.Instant;

public record ModerationCaseEventInsert(
        String id,
        String moderationCaseId,
        String listingId,
        String eventType,
        String actorUserId,
        String previousState,
        String newState,
        String previousAssignedAdminUserId,
        String newAssignedAdminUserId,
        String reason,
        String correlationId,
        Instant occurredAt
) {
}
