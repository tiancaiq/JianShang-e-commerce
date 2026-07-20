package com.msb.ecom.order_service.dto;

import java.time.Instant;
import java.util.List;

public record CartResponse(
        long version,
        Instant expiresAt,
        int itemCount,
        int totalQuantity,
        List<CartCurrencyTotalResponse> totals,
        List<CartItemResponse> items
) {
}
