package com.msb.ecom.product_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InternalReportListingContext(
        String listingId,
        String sellerType,
        String individualSellerUserId,
        String businessId,
        String storeId,
        String title,
        String description,
        BigDecimal price,
        String currency,
        String categoryId,
        String sku,
        String status,
        List<String> imageReferences,
        Instant capturedAt,
        long version,
        boolean reportable,
        List<EnforcementSummary> enforcement) {

    public record EnforcementSummary(String actionType, List<String> scopes) { }
}
