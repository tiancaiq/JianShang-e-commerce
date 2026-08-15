package com.msb.ecom.product_service.search.hybrid;

import java.math.BigDecimal;

public record ListingHybridSearchRequest(
        String query,
        float[] embedding,
        Filters filters,
        int limit,
        String responseSchemaVersion
) {

    public ListingHybridSearchRequest(
            String query, float[] embedding, Filters filters, int limit) {
        this(query, embedding, filters, limit, ListingHybridSearchResponse.SCHEMA_VERSION);
    }

    public ListingHybridSearchRequest {
        embedding = embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding.clone();
    }

    public record Filters(
            String categoryId,
            String condition,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String currency,
            String city,
            String publicRegion
    ) {
    }
}
