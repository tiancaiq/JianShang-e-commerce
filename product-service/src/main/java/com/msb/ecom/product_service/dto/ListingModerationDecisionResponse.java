package com.msb.ecom.product_service.dto;

import java.time.Instant;

public record ListingModerationDecisionResponse(
        String id,
        String listingId,
        String decision,
        String reason,
        String reviewerUserId,
        long listingVersion,
        Instant createdAt
) {
}
