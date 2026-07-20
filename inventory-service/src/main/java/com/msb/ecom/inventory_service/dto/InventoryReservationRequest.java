package com.msb.ecom.inventory_service.dto;

import com.msb.ecom.inventory_service.model.ReservationPurpose;

import java.time.Instant;
import java.util.List;

public record InventoryReservationRequest(
        String checkoutId,
        ReservationPurpose purpose,
        Instant expiresAt,
        List<Item> items
) {
    public record Item(String listingId, Integer quantity) {
    }
}
