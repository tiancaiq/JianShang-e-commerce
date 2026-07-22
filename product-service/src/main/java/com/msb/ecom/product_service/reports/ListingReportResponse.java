package com.msb.ecom.product_service.reports;

import java.time.Instant;

public record ListingReportResponse(
        String reportId,
        String status,
        String listingId,
        String reasonCode,
        String policyVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
