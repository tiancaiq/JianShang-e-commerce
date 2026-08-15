package com.msb.ecom.product_service.dto;

public record AgentMarketplaceAvailabilityResponse(
        String schemaVersion,
        String mode,
        boolean searchExecuted,
        String category,
        long totalActiveCategoryInventory,
        long relatedCategoryMatches,
        String failureReason,
        boolean retryable
) {
    public static final String SCHEMA_VERSION = "MARKETPLACE_AVAILABILITY_PROBE_V1";
    public static final String MODE = "AVAILABILITY_PROBE";
}
