package com.msb.ecom.product_service.dto;

import java.time.Instant;

public record ListingImageResponse(
        String id,
        String listingId,
        String mediaObjectId,
        int displayOrder,
        String altText,
        String moderationStatus,
        String originalFileName,
        String contentType,
        long sizeBytes,
        String uploadStatus,
        String objectBucket,
        String objectKey,
        String uploadUrl,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
