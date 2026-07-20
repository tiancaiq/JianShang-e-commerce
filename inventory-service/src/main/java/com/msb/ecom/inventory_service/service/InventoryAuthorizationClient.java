package com.msb.ecom.inventory_service.service;

public interface InventoryAuthorizationClient {

    BusinessAuthorization requirePermission(String bearerToken, String businessId, String permission);

    record BusinessAuthorization(
            String businessId,
            String userId,
            String role
    ) {
    }
}
