package com.msb.ecom.inventory_service.dto;

import com.msb.ecom.inventory_service.model.ReservationPurpose;
import com.msb.ecom.inventory_service.model.ReservationStatus;

import java.time.Instant;
import java.util.List;

public record InventoryReservationResponse(
        String id,
        String checkoutId,
        ReservationPurpose purpose,
        ReservationStatus status,
        boolean usable,
        Instant expiresAt,
        Instant committedAt,
        Instant releasedAt,
        String releaseReason,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<InventoryReservationItemResponse> items
) {
}
