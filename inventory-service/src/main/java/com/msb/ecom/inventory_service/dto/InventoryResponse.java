package com.msb.ecom.inventory_service.dto;

import java.time.Instant;

public record InventoryResponse(
        String id,
        String businessId,
        String listingId,
        String skuSnapshot,
        int onHand,
        int reserved,
        int available,
        long version,
        Instant initializedAt,
        Instant updatedAt
) {
}
