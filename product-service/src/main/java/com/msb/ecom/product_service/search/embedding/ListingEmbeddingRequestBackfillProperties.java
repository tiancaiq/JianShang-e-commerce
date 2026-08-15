package com.msb.ecom.product_service.search.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "listing.search.embedding.backfill")
public record ListingEmbeddingRequestBackfillProperties(
        boolean commandsEnabled,
        boolean statusEnabled,
        int pageSize,
        int maxListings,
        Duration leaseDuration
) {
    public ListingEmbeddingRequestBackfillProperties {
        if (pageSize < 1 || pageSize > 500) {
            throw new IllegalArgumentException("Listing embedding backfill page size is invalid.");
        }
        if (maxListings < pageSize || maxListings > 1_000_000) {
            throw new IllegalArgumentException("Listing embedding backfill maximum is invalid.");
        }
        if (leaseDuration == null
                || leaseDuration.isNegative()
                || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Listing embedding backfill lease is invalid.");
        }
    }
}
