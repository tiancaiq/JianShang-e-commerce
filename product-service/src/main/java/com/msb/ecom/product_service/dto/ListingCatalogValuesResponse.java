package com.msb.ecom.product_service.dto;

import java.util.Map;

public record ListingCatalogValuesResponse(
        String listingId,
        String categoryId,
        long categoryRuleVersion,
        Map<String, Object> attributes
) {
}
