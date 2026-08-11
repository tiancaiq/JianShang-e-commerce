package com.msb.ecom.inventory_service.dto;

import java.time.Instant;

public record InventoryCancellationRestockResponse(
        String restockId,
        String orderId,
        String cancellationRequestId,
        String reservationId,
        String status,
        int restoredQuantity,
        Instant completedAt
) {
}
