package com.msb.ecom.product_service.listing;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingDraftInsert(
        String id,
        ListingSellerType sellerType,
        String individualSellerUserId,
        String businessId,
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
        Instant now
) {
}
