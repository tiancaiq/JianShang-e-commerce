package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingSellerType;

import java.time.Instant;

public record ModerationCaseInsert(
        String id,
        String listingId,
        ListingSellerType sellerType,
        String individualSellerUserId,
        String businessId,
        String submittedByUserId,
        Instant now
) {
}
