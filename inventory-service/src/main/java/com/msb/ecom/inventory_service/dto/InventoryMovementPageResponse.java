package com.msb.ecom.inventory_service.dto;

import java.util.List;

public record InventoryMovementPageResponse(
        List<InventoryMovementResponse> data,
        PageMetadata page
) {
    public record PageMetadata(String nextCursor, boolean hasMore) {
    }
}
