package com.msb.ecom.product_service.repository;

import java.time.Instant;

public record ListingModerationDecisionInsert(
        String id,
        String listingId,
        String moderationCaseId,
        String decision,
        String reason,
        String reviewerUserId,
        long listingVersion,
        String previousState,
        String newState,
        String correlationId,
        Instant now
) {
}
