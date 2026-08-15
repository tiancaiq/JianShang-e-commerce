package com.msb.ecom.order_service.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CartStoredItem(
        String listingId,
        int quantity,
        BigDecimal observedPrice,
        String currency,
        Instant addedAt,
        Instant updatedAt
) {

    public CartStoredItem(
            String listingId,
            int quantity,
            BigDecimal observedPrice,
            String currency,
            Instant addedAt) {
        this(listingId, quantity, observedPrice, currency, addedAt, addedAt);
    }

    public Instant lineIdentity() {
        return updatedAt == null ? addedAt : updatedAt;
    }
}
