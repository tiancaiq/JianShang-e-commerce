package com.msb.ecom.order_service.service;

import java.time.Instant;
import java.util.List;

public interface InventoryReservationClient {

    Reservation reserve(String checkoutId, Instant expiresAt, List<Line> lines);

    Reservation get(String reservationId);

    Reservation release(String checkoutId, String reservationId, String reason);

    Reservation commit(String checkoutId, String reservationId, String correlationId);

    record Line(String listingId, int quantity) {
    }

    record Reservation(
            String id,
            String checkoutId,
            String purpose,
            String status,
            boolean usable,
            Instant expiresAt,
            long version,
            List<Item> items
    ) {
    }

    record Item(String listingId, int quantity) {
    }
}
