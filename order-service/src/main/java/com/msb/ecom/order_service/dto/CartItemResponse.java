package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CartItemResponse(
        String listingId,
        String title,
        String thumbnailUrl,
        String storeName,
        String storeSlug,
        Boolean businessVerified,
        String publicCity,
        String publicRegion,
        int quantity,
        BigDecimal observedPrice,
        String currency,
        Instant addedAt
) {
}
