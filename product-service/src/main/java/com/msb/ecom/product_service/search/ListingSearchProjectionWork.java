package com.msb.ecom.product_service.search;

import java.time.Instant;

public record ListingSearchProjectionWork(
        String workId,
        String listingId,
        long listingVersion,
        String operation,
        int attemptCount,
        Instant createdAt
) {
}
