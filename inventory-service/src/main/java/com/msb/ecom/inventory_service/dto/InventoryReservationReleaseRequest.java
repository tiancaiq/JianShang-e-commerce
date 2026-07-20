package com.msb.ecom.inventory_service.dto;

import com.msb.ecom.inventory_service.model.ReservationReleaseReason;

public record InventoryReservationReleaseRequest(ReservationReleaseReason reason) {
}
