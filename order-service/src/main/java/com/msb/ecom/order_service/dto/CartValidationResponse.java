package com.msb.ecom.order_service.dto;

import java.time.Instant;
import java.util.List;

public record CartValidationResponse(
        long cartVersion,
        Instant validatedAt,
        boolean checkoutReady,
        int itemCount,
        int totalQuantity,
        List<CartCurrencyTotalResponse> validatedTotals,
        List<CartValidationIssueResponse> cartIssues,
        List<CartValidationItemResponse> items
) {
}
