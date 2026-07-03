package com.msb.ecom.product_service.search;

public record ListingSearchRebuildResponse(
        String engine,
        String index,
        int indexedCount
) {
}
