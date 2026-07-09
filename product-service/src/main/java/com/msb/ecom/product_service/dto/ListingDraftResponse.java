package com.msb.ecom.product_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ListingDraftResponse(
        String id,
        String sellerType,
        String individualSellerUserId,
        String businessId,
        String storeId,
        String sellerDisplayName,
        String categoryId,
        String title,
        String description,
        String condition,
        String conditionNotes,
        BigDecimal priceAmount,
        String currency,
        boolean negotiable,
        String sku,
        int quantity,
        String publicCity,
        String publicRegion,
        String status,
        String moderationStatus,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<ListingImageResponse> images
) {
    public ListingDraftResponse withSellerDisplayName(String displayName) {
        return new ListingDraftResponse(
                id,
                sellerType,
                individualSellerUserId,
                businessId,
                storeId,
                displayName,
                categoryId,
                title,
                description,
                condition,
                conditionNotes,
                priceAmount,
                currency,
                negotiable,
                sku,
                quantity,
                publicCity,
                publicRegion,
                status,
                moderationStatus,
                version,
                createdAt,
                updatedAt,
                images);
    }
}
