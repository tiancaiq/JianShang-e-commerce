package com.msb.ecom.product_service.dto;

public record ListingEngagementResponse(
        String listingId,
        long visitCount,
        long likeCount,
        boolean visitedByMe,
        boolean likedByMe
) {
}
