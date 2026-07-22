package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.util.List;

public record CartValidationItemResponse(
        String listingId,
        String title,
        String thumbnailUrl,
        String storeName,
        String storeSlug,
        Boolean businessVerified,
        String publicCity,
        String publicRegion,
        int requestedQuantity,
        Integer availableQuantity,
        BigDecimal observedPrice,
        BigDecimal currentPrice,
        String observedCurrency,
        String currentCurrency,
        String status,
        List<CartValidationIssueResponse> issues
) {
}
