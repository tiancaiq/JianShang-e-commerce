package com.msb.ecom.product_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminListingModerationCaseResponse(
        String id,
        String caseStatus,
        String priority,
        String assignedAdminUserId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        String submittedByUserId,
        String sellerId,
        String sellerDisplayName,
        String assignedAdminDisplayName,
        String listingId,
        String title,
        String sellerType,
        String listingStatus,
        String listingModerationStatus,
        BigDecimal priceAmount,
        String currency,
        String publicCity,
        String publicRegion,
        String sku,
        int quantity
) {
    public AdminListingModerationCaseResponse withIdentityLabels(
            String sellerDisplayName,
            String assignedAdminDisplayName) {
        return new AdminListingModerationCaseResponse(
                id,
                caseStatus,
                priority,
                assignedAdminUserId,
                version,
                createdAt,
                updatedAt,
                resolvedAt,
                submittedByUserId,
                sellerId,
                sellerDisplayName,
                assignedAdminDisplayName,
                listingId,
                title,
                sellerType,
                listingStatus,
                listingModerationStatus,
                priceAmount,
                currency,
                publicCity,
                publicRegion,
                sku,
                quantity);
    }
}
