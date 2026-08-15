package com.msb.ecom.product_service.search.embedding;

import java.time.Instant;

public record ListingEmbeddingRequestBackfillRun(
        String runId,
        String upperBoundListingId,
        String lastProcessedListingId,
        String state,
        int pageCount,
        int processedCount,
        int createdCount,
        int alreadyPresentCount,
        int skippedCount,
        int failedCount,
        String lastErrorCode,
        String leaseToken,
        Instant leaseExpiresAt,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt
) {
    public boolean completed() {
        return "COMPLETED".equals(state);
    }
}
