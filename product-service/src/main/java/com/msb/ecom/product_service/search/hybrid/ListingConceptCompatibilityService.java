package com.msb.ecom.product_service.search.hybrid;

import com.msb.ecom.product_service.dto.PublicListingResponse;
import org.springframework.stereotype.Component;

@Component
public final class ListingConceptCompatibilityService {

    private final ListingConceptCompatibilityReranker reranker;

    public ListingConceptCompatibilityService(ListingConceptRankingProperties properties) {
        this.reranker = new ListingConceptCompatibilityReranker(properties);
    }

    // Applies Product's bounded concept gate to a MySQL-revalidated public listing.
    public boolean productTypeCompatible(String query, PublicListingResponse listing) {
        var candidate = new ListingHybridSearchListing(
                listing.id(),
                0L,
                listing.categoryId(),
                listing.categorySlug(),
                listing.categoryName(),
                listing.title(),
                listing.condition(),
                listing.priceAmount(),
                listing.currency(),
                listing.publicCity(),
                listing.publicRegion(),
                listing.quantity() > 0,
                null,
                listing.publishedAt(),
                listing.description(),
                listing.sellerType(),
                listing.storeId());
        return reranker.assess(ListingQueryConcept.interpret(query), candidate)
                .productTypeCompatible();
    }
}
