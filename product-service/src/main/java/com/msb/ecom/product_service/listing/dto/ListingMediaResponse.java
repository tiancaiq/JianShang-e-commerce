package com.msb.ecom.product_service.listing.dto;

import java.time.Instant;

public record ListingMediaResponse(
        String id,
        String listingId,
        String sellerType,
        String individualSellerUserId,
        String businessId,
        String objectBucket,
        String objectKey,
        String originalFileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String uploadStatus,
        String moderationStatus,
        String uploadMethod,
        String uploadUrl,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
