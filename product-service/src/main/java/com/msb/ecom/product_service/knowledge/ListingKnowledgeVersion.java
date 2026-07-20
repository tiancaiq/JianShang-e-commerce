package com.msb.ecom.product_service.knowledge;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingKnowledgeVersion(
        String listingId,
        long sourceVersion,
        Long supersedesVersion,
        String lifecycle,
        String sellerType,
        String visibility,
        String language,
        String title,
        String description,
        BigDecimal priceAmount,
        String currency,
        String publicCity,
        String publicRegion,
        String contentHash,
        Instant effectiveFrom,
        Instant invalidatedAt,
        Instant sourcePublishedAt,
        Instant createdAt
) {
}
