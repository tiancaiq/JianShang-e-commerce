package com.msb.ecom.auth_service.dto;

import java.time.Instant;

public record BusinessStoreResponse(
        String id,
        String businessId,
        String slug,
        String name,
        String description,
        String logoUrl,
        String bannerUrl,
        String supportEmail,
        String supportPhone,
        String publicCity,
        String publicRegion,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
