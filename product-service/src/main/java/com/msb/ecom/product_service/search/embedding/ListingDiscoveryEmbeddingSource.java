package com.msb.ecom.product_service.search.embedding;

public record ListingDiscoveryEmbeddingSource(
        String documentSchemaVersion,
        String documentHash,
        String embeddingInputSchemaVersion,
        String embeddingInputHash,
        String normalizerVersion,
        String redactorVersion,
        String language,
        String embeddingText
) {
}
