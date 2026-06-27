package com.msb.ecom.product_service.repository;

import java.time.Instant;

public record ListingImageInsert(
        String id,
        String listingId,
        String mediaObjectId,
        int displayOrder,
        String altText,
        Instant now
) {
}
