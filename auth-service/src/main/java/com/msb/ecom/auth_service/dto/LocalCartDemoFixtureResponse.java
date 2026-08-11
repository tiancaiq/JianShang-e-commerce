package com.msb.ecom.auth_service.dto;

public record LocalCartDemoFixtureResponse(
        String applicationId,
        String businessId,
        String storeId,
        String ownerUserId,
        String storeName,
        String storeSlug,
        boolean verified,
        String publicCity,
        String publicRegion
) {
}
