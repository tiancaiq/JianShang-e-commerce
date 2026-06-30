package com.msb.ecom.product_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PublicListingResponse(
        String id,
        String sellerType,
        String categoryId,
        String categorySlug,
        String categoryName,
        String title,
        String description,
        String condition,
        String conditionNotes,
        BigDecimal priceAmount,
        String currency,
        boolean negotiable,
        int quantity,
        String publicCity,
        String publicRegion,
        Instant publishedAt,
        String transactionNotice,
        List<PublicListingImageResponse> images
) {
}
