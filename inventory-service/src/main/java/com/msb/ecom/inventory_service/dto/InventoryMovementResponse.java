package com.msb.ecom.inventory_service.dto;

import java.time.Instant;

public record InventoryMovementResponse(
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
