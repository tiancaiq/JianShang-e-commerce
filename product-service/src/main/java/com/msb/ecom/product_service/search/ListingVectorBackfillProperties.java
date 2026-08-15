package com.msb.ecom.product_service.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "listing.search.vector-backfill")
public record ListingVectorBackfillProperties(
        boolean enabled,
        String physicalIndexPrefix,
        int batchSize,
        int maxDocuments,
        int maxBulkBytes,
        boolean cleanupFailedGeneration
) {

    public ListingVectorBackfillProperties {
        if (physicalIndexPrefix == null
                || !physicalIndexPrefix.matches("[a-z0-9][a-z0-9_-]{2,80}")) {
            throw new IllegalArgumentException("Listing vector index prefix is invalid.");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Listing vector backfill batch size is invalid.");
        }
        if (maxDocuments < 1 || maxDocuments > 1_000_000) {
            throw new IllegalArgumentException("Listing vector backfill document limit is invalid.");
        }
        if (maxBulkBytes < 64_000 || maxBulkBytes > 10_000_000) {
            throw new IllegalArgumentException("Listing vector backfill bulk byte limit is invalid.");
        }
    }
}
