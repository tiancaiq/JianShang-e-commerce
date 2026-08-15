package com.msb.ecom.product_service.search.embedding;

import java.time.Instant;

public record ListingDiscoveryEmbeddingReceipt(
        String requestId,
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
        String vectorHash,
        byte[] vectorBytes,
        Instant acceptedAt
) {

    public ListingDiscoveryEmbeddingReceipt {
        vectorBytes = vectorBytes.clone();
    }

    @Override
    public byte[] vectorBytes() {
        return vectorBytes.clone();
    }
}
