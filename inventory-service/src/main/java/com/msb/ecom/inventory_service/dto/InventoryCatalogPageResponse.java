package com.msb.ecom.inventory_service.dto;

import java.util.List;

public record InventoryCatalogPageResponse(
        List<InventoryCatalogItemResponse> data,
        PageMetadata page
) {
    public record PageMetadata(String nextCursor, boolean hasMore) {
    }
}
