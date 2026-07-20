package com.msb.ecom.inventory_service.repository;

import java.time.Instant;

public record InventoryMovementRecord(
        String id,
        String operation,
        String reason,
        int quantityDelta,
        int onHandBefore,
        int onHandAfter,
        int reservedSnapshot,
        String note,
        Instant createdAt
) {
}
