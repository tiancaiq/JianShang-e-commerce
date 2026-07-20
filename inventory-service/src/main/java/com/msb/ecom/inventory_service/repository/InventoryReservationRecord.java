package com.msb.ecom.inventory_service.repository;

import com.msb.ecom.inventory_service.model.ReservationPurpose;
import com.msb.ecom.inventory_service.model.ReservationStatus;

import java.time.Instant;

public record InventoryReservationRecord(
        String id,
        String checkoutId,
        ReservationPurpose purpose,
        ReservationStatus status,
        Instant expiresAt,
        Instant committedAt,
        Instant releasedAt,
        String releaseReason,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
