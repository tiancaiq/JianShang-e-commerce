package com.msb.ecom.product_service.search.embedding;

import java.time.Instant;

public record ListingDiscoveryEmbeddingRequest(
        String requestId,
        String eventId,
        String listingId,
        long listingVersion,
        String documentSchemaVersion,
        String documentHash,
        String embeddingInputSchemaVersion,
        String embeddingInputHash,
        String normalizerVersion,
        String redactorVersion,
        String language,
        String provider,
        String model,
        int dimensions,
        String state,
        Instant createdAt
) {
}
