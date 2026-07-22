package com.msb.ecom.order_service.dto;

import java.time.Instant;

public record OrderCancellationResponse(
        String orderId,
        String cancellationRequestId,
        String status,
        String requestStatus,
        long version,
        Instant requestedAt
) {
}
