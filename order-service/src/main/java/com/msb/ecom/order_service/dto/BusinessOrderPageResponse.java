package com.msb.ecom.order_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BusinessOrderPageResponse(
        List<OrderSummary> items,
        PageMetadata page
) {

    public record OrderSummary(
            String businessOrderId,
            String sellerOrderNumber,
            String businessId,
            String storeId,
            String status,
            String cancellationStatus,
            String buyerOrderId,
            String buyerOrderNumber,
            int itemCount,
            int totalQuantity,
            BigDecimal subtotal,
            BigDecimal totalAmount,
            String currency,
            @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal platformFeeProjection,
            Instant confirmedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record PageMetadata(
            String nextCursor,
            boolean hasMore
    ) {
    }
}
