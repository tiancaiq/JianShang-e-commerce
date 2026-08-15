package com.msb.ecom.product_service.search;

import java.util.Arrays;

public record ListingVectorBackfillDocument(
        ListingSearchDocument listing,
        long listingVersion,
        String documentSchemaVersion,
        String documentHash,
        String embeddingInputSchemaVersion,
        String embeddingInputHash,
        String normalizerVersion,
        String redactorVersion,
        String language,
        String embeddingProvider,
        String embeddingModel,
        int embeddingDimensions,
        String embeddingState,
        float[] embedding
) {

    public ListingVectorBackfillDocument {
        embedding = embedding == null ? null : embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }

    public boolean hasEmbedding() {
        return embedding != null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ListingVectorBackfillDocument that)) {
            return false;
        }
        return listingVersion == that.listingVersion
                && embeddingDimensions == that.embeddingDimensions
                && java.util.Objects.equals(listing, that.listing)
                && java.util.Objects.equals(documentSchemaVersion, that.documentSchemaVersion)
                && java.util.Objects.equals(documentHash, that.documentHash)
                && java.util.Objects.equals(embeddingInputSchemaVersion, that.embeddingInputSchemaVersion)
                && java.util.Objects.equals(embeddingInputHash, that.embeddingInputHash)
                && java.util.Objects.equals(normalizerVersion, that.normalizerVersion)
                && java.util.Objects.equals(redactorVersion, that.redactorVersion)
                && java.util.Objects.equals(language, that.language)
                && java.util.Objects.equals(embeddingProvider, that.embeddingProvider)
                && java.util.Objects.equals(embeddingModel, that.embeddingModel)
                && java.util.Objects.equals(embeddingState, that.embeddingState)
                && Arrays.equals(embedding, that.embedding);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(
                listing,
                listingVersion,
                documentSchemaVersion,
                documentHash,
                embeddingInputSchemaVersion,
                embeddingInputHash,
                normalizerVersion,
                redactorVersion,
                language,
                embeddingProvider,
                embeddingModel,
                embeddingDimensions,
                embeddingState);
        return 31 * result + Arrays.hashCode(embedding);
    }
}
