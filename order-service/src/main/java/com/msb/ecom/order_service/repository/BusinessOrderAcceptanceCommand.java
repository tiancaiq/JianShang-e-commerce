package com.msb.ecom.order_service.repository;

import java.time.Instant;

public record BusinessOrderAcceptanceCommand(
        String id,
        String requestHash,
        String state,
        String businessOrderId,
        Long resultVersion,
        Instant resultUpdatedAt
) {
}
