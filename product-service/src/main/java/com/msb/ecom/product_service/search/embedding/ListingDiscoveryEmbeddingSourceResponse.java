package com.msb.ecom.product_service.search.embedding;

public record ListingDiscoveryEmbeddingSourceResponse(
        String schemaVersion,
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
        EmbeddingIdentity embeddingIdentity,
        String embeddingText
) {

    public record EmbeddingIdentity(
            String provider,
            String model,
            int dimensions
    ) {
    }
}
