package com.msb.ecom.order_service.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CartStoredItem(
        String listingId,
        int quantity,
        BigDecimal observedPrice,
        String currency,
        Instant addedAt
) {
}
