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
}
