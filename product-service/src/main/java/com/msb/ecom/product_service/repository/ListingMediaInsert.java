package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingSellerType;

import java.time.Instant;

public record ListingMediaInsert(
        String id,
        String listingId,
        ListingSellerType sellerType,
        String individualSellerUserId,
        String businessId,
        String objectBucket,
        String objectKey,
        String originalFileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        Instant now
) {
}
