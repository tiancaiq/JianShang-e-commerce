package com.msb.ecom.product_service.dto;

public record ChatListingEligibilityResponse(
        String listingId,
        boolean eligible,
        String sellerType,
        String sellerUserId,
        Integer quantity,
        String title,
        String publicCity,
        String publicRegion,
        String thumbnailUrl,
        String transactionNotice
) {
}
