package com.msb.ecom.auth_service.dto;

import java.util.List;

public record BusinessStoreContextResponse(
        String businessId,
        String businessLegalName,
        String businessStatus,
        String membershipRole,
        List<String> permissions,
        BusinessStoreResponse store
) {
}
