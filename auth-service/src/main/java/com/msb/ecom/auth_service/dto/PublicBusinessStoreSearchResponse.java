package com.msb.ecom.auth_service.dto;

public record PublicBusinessStoreSearchResponse(
        String businessId,
        String storeId,
        String storeName,
        String businessLegalName
) {
}
