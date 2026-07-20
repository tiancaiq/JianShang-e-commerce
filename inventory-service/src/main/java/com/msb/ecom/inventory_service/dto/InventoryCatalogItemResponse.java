package com.msb.ecom.inventory_service.dto;

public record InventoryCatalogItemResponse(
        String listingId,
        String title,
        String sku,
        String listingStatus,
        int catalogQuantitySuggestion,
        long catalogVersion,
        String inventoryState,
        InventoryResponse inventory
) {
}
