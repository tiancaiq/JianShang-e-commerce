package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BuyerOrderPageResponse(
        List<OrderSummary> items,
        PageMetadata page
) {

    public record OrderSummary(
            String orderId,
            String status,
            String paymentStatus,
            BigDecimal totalAmount,
            String currency,
            Instant createdAt,
            Instant updatedAt,
            List<GroupSummary> groups
    ) {
    }

    public record GroupSummary(
            String businessOrderId,
            String businessId,
            String status,
            BigDecimal totalAmount,
            String currency
    ) {
    }

    public record PageMetadata(
            String nextCursor,
            boolean hasMore
    ) {
    }
}
