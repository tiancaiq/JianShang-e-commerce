package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingCondition;
import com.msb.ecom.product_service.model.ListingSellerType;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingDraftInsert(
        String id,
        ListingSellerType sellerType,
        String individualSellerUserId,
        String businessId,
        String storeId,
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
        String publicRegion,
        long categoryRuleVersion,
        Instant now
) {
}
