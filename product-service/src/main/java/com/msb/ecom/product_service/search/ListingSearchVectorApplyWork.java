package com.msb.ecom.product_service.search;

import java.time.Instant;

public record ListingSearchVectorApplyWork(
        String workId,
        String requestId,
        String listingId,
        long listingVersion,
        int attemptCount,
        Instant createdAt
) {
}
