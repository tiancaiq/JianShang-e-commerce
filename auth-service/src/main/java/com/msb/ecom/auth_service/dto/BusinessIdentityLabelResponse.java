package com.msb.ecom.auth_service.dto;

public record BusinessIdentityLabelResponse(
        String id,
        String legalName,
        String storeId,
        String storeSlug,
        String storeName,
        String publicCity,
        String publicRegion,
        boolean verified
) {
}
