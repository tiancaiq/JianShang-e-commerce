package com.msb.ecom.inventory_service.repository;

import java.time.Instant;

public record InventoryItemRecord(
        String id,
        String businessId,
        String listingId,
        String skuSnapshot,
        long catalogVersionSnapshot,
        int onHand,
        int reserved,
        long version,
        Instant initializedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
