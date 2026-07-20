package com.msb.ecom.inventory_service.dto;

public record InventoryReservationItemResponse(
        String inventoryItemId,
        String businessId,
        String listingId,
        int quantity,
        int onHand,
        int reserved,
        int available,
        long inventoryVersion
) {
}
