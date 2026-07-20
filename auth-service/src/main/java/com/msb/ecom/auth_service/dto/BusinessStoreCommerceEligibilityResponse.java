package com.msb.ecom.auth_service.dto;

public record BusinessStoreCommerceEligibilityResponse(
        String businessId,
        String storeId,
        boolean eligible,
        String businessStatus,
        String storeStatus
) {
}
