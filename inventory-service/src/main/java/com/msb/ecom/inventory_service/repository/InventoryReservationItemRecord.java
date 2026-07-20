package com.msb.ecom.inventory_service.repository;

import java.time.Instant;

public record InventoryReservationItemRecord(
        String id,
        String reservationId,
        String inventoryItemId,
        String businessId,
        String listingId,
        int quantity,
        Instant createdAt,
        Instant updatedAt
) {
}
