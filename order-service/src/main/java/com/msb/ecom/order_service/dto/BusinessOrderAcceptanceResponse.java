package com.msb.ecom.order_service.dto;

import java.time.Instant;

public record BusinessOrderAcceptanceResponse(
        String businessOrderId,
        String fulfillmentStatus,
        long version,
        Instant updatedAt
) {
}
