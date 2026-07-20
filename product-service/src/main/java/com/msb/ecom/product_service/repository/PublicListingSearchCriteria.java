package com.msb.ecom.product_service.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PublicListingSearchCriteria(
        String keyword,
        String categoryId,
        String condition,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String city,
        String county,
        String sort,
        List<String> businessIds,
        List<String> visibleBusinessIds,
        BigDecimal cursorPrice,
        Instant cursorPublishedAt,
        String cursorListingId) {

    public PublicListingSearchCriteria withBusinessIds(List<String> businessIds) {
        return new PublicListingSearchCriteria(
                keyword,
                categoryId,
                condition,
                minPrice,
                maxPrice,
                city,
                county,
                sort,
                businessIds == null ? List.of() : businessIds,
                visibleBusinessIds,
                cursorPrice,
                cursorPublishedAt,
                cursorListingId);
    }

    public PublicListingSearchCriteria withVisibleBusinessIds(List<String> visibleBusinessIds) {
        return new PublicListingSearchCriteria(
                keyword,
                categoryId,
                condition,
                minPrice,
                maxPrice,
                city,
                county,
                sort,
                businessIds,
                visibleBusinessIds == null ? List.of() : visibleBusinessIds,
                cursorPrice,
                cursorPublishedAt,
                cursorListingId);
    }

    // Clears location predicates after auth-service has resolved the public business-store location.
    public PublicListingSearchCriteria withoutLocationFilters() {
        return new PublicListingSearchCriteria(
                keyword,
                categoryId,
                condition,
                minPrice,
                maxPrice,
                null,
                null,
                sort,
                businessIds,
                visibleBusinessIds,
                cursorPrice,
                cursorPublishedAt,
                cursorListingId);
    }
}
