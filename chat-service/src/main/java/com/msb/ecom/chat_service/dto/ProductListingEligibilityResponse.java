package com.msb.ecom.chat_service.dto;

public record ProductListingEligibilityResponse(
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
