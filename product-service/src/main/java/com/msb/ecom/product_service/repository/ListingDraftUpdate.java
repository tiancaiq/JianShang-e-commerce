package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingCondition;

import java.math.BigDecimal;

public record ListingDraftUpdate(
        String categoryId,
        String title,
        String description,
        ListingCondition condition,
        String conditionNotes,
        BigDecimal priceAmount,
        String currency,
        boolean negotiable,
        String sku,
        int quantity,
        String publicCity,
        String publicRegion
) {
}
