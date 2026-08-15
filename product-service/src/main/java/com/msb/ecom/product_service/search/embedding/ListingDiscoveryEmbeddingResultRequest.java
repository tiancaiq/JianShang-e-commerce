package com.msb.ecom.product_service.search.embedding;

public record ListingDiscoveryEmbeddingResultRequest(
        String schemaVersion,
        long listingVersion,
        String documentSchemaVersion,
        String documentHash,
        String embeddingInputSchemaVersion,
        String embeddingInputHash,
        EmbeddingIdentity embeddingIdentity,
        ListingDiscoveryEmbeddingVectorCodec.CanonicalVector vector
) {

    public record EmbeddingIdentity(
            String provider,
            String model,
            int dimensions
    ) {
    }
}
