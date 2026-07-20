package com.msb.ecom.product_service.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PublicListingResponse(
        String id,
        String sellerType,
        @JsonIgnore
        String sellerId,
        String sellerDisplayName,
        String sellerAvatarUrl,
        String storeId,
        String storeSlug,
        String storeName,
        boolean businessVerified,
        String categoryId,
        String categorySlug,
        String categoryName,
        String title,
        String description,
        String condition,
        String conditionNotes,
        BigDecimal priceAmount,
        String currency,
        boolean negotiable,
        int quantity,
        String publicCity,
        String publicRegion,
        Instant publishedAt,
        String transactionNotice,
        long visitCount,
        long likeCount,
        List<PublicListingImageResponse> images
) {
    public PublicListingResponse withSellerDisplayName(String displayName) {
        return new PublicListingResponse(
                id,
                sellerType,
                sellerId,
                displayName,
                sellerAvatarUrl,
                storeId,
                storeSlug,
                storeName,
                businessVerified,
                categoryId,
                categorySlug,
                categoryName,
                title,
                description,
                condition,
                conditionNotes,
                priceAmount,
                currency,
                negotiable,
                quantity,
                publicCity,
                publicRegion,
                publishedAt,
                transactionNotice,
                visitCount,
                likeCount,
                images);
    }

    public PublicListingResponse withSellerLabel(String displayName, String avatarUrl) {
        return new PublicListingResponse(
                id,
                sellerType,
                sellerId,
                displayName,
                avatarUrl,
                storeId,
                storeSlug,
                storeName,
                businessVerified,
                categoryId,
                categorySlug,
                categoryName,
                title,
                description,
                condition,
                conditionNotes,
                priceAmount,
                currency,
                negotiable,
                quantity,
                publicCity,
                publicRegion,
                publishedAt,
                transactionNotice,
                visitCount,
                likeCount,
                images);
    }

    public PublicListingResponse withBusinessStoreIdentity(
            String displayName,
            String publicStoreId,
            String publicStoreSlug,
            String publicStoreName,
            boolean verified,
            String storeCity,
            String storeRegion) {
        return new PublicListingResponse(
                id,
                sellerType,
                sellerId,
                displayName,
                sellerAvatarUrl,
                publicStoreId,
                publicStoreSlug,
                publicStoreName,
                verified,
                categoryId,
                categorySlug,
                categoryName,
                title,
                description,
                condition,
                conditionNotes,
                priceAmount,
                currency,
                negotiable,
                quantity,
                publicCity == null ? storeCity : publicCity,
                publicRegion == null ? storeRegion : publicRegion,
                publishedAt,
                transactionNotice,
                visitCount,
                likeCount,
                images);
    }
}
