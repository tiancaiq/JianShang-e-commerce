package com.msb.ecom.product_service.dto;

import java.math.BigDecimal;

public record BusinessStoreItemCommerceContextResponse(
        String listingId,
        String businessId,
        String storeId,
        String storeName,
        String storeSlug,
        boolean businessVerified,
        String publicCity,
        String publicRegion,
        String sellerType,
        String title,
        String sku,
        String condition,
        BigDecimal priceAmount,
        String currency,
        int quantity,
        String status,
        String publicationSource,
        long version,
        String thumbnailUrl
) {
    public static BusinessStoreItemCommerceContextResponse from(ListingDraftResponse listing) {
        return from(listing, null, null, null, false, null, null);
    }

    public static BusinessStoreItemCommerceContextResponse from(
            ListingDraftResponse listing,
            String thumbnailUrl,
            String storeName,
            String storeSlug,
            boolean businessVerified,
            String publicCity,
            String publicRegion) {
        return new BusinessStoreItemCommerceContextResponse(
                listing.id(),
                listing.businessId(),
                listing.storeId(),
                storeName,
                storeSlug,
                businessVerified,
                publicCity,
                publicRegion,
                listing.sellerType(),
                listing.title(),
                listing.sku(),
                listing.condition(),
                listing.priceAmount(),
                listing.currency(),
                listing.quantity(),
                listing.status(),
                listing.publicationSource(),
                listing.version(),
                thumbnailUrl);
    }
}
