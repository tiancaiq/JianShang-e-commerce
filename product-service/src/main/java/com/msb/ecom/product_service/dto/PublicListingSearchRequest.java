package com.msb.ecom.product_service.dto;

import java.math.BigDecimal;

public record PublicListingSearchRequest(
        String q,
        String categoryId,
        String condition,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String city,
        String county,
        String sort,
        String cursor,
        Integer limit) {
}
