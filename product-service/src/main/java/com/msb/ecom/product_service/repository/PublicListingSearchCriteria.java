package com.msb.ecom.product_service.repository;

import java.math.BigDecimal;
import java.time.Instant;

public record PublicListingSearchCriteria(
        String keyword,
        String categoryId,
        String condition,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String city,
        String county,
        String sort,
        BigDecimal cursorPrice,
        Instant cursorPublishedAt,
        String cursorListingId) {
}
