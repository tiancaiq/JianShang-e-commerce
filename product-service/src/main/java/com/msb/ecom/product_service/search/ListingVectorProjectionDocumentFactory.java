package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingImageResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSource;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ListingVectorProjectionDocumentFactory {

    private final ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;

    // Builds a lexical V2 state that invalidates an older vector without creating a new vector.
    public ListingVectorBackfillDocument lexical(
            ListingDraftResponse listing,
            PublicListingResponse publicListing,
            List<PublicListingImageResponse> images) {
        ListingSearchDocument lexical = ListingSearchDocument.from(publicListing, images);
        if (!"INDIVIDUAL".equals(listing.sellerType())) {
            return new ListingVectorBackfillDocument(
                    lexical,
                    listing.version(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    "NOT_APPLICABLE",
                    null);
        }
        ListingDiscoveryEmbeddingSource source =
                sourceBuilder.build(publicListing, listing.version(), images);
        return new ListingVectorBackfillDocument(
                lexical,
                listing.version(),
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                ListingDiscoveryEmbeddingSourceBuilder.PROVIDER,
                ListingDiscoveryEmbeddingSourceBuilder.MODEL,
                ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS,
                "MISSING",
                null);
    }

    // Creates one complete vector-bearing V2 document from current Product public facts.
    public ListingVectorBackfillDocument vector(
            ListingDraftResponse listing,
            PublicListingResponse publicListing,
            List<PublicListingImageResponse> images,
            ListingDiscoveryEmbeddingSource source,
            float[] embedding) {
        ListingSearchDocument lexical = ListingSearchDocument.from(publicListing, images);
        return new ListingVectorBackfillDocument(
                lexical,
                listing.version(),
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                ListingDiscoveryEmbeddingSourceBuilder.PROVIDER,
                ListingDiscoveryEmbeddingSourceBuilder.MODEL,
                ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS,
                "ATTACHED",
                embedding);
    }
}
