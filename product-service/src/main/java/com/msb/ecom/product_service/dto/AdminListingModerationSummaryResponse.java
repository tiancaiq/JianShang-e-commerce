package com.msb.ecom.product_service.dto;

public record AdminListingModerationSummaryResponse(
        long pendingListingReviews,
        long assignedToMeListingReviews
) {
}
