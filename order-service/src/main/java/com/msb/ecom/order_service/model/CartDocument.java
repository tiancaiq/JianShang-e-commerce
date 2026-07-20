package com.msb.ecom.order_service.model;

import java.time.Instant;
import java.util.List;

public record CartDocument(
        long version,
        Instant expiresAt,
        List<CartStoredItem> items
) {
    public static CartDocument empty() {
        return new CartDocument(0, null, List.of());
    }
}
