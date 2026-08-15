package com.msb.ecom.inventory_service.dto;

public record InventoryCancellationRestockRequest(
        String orderId,
        String cancellationRequestId
) {
}
