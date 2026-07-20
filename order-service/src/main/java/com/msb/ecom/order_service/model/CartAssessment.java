package com.msb.ecom.order_service.model;

import com.msb.ecom.order_service.dto.CartValidationIssueResponse;
import com.msb.ecom.order_service.service.ProductCommerceClient;

import java.time.Instant;
import java.util.List;

public record CartAssessment(
        CartDocument cart,
        Instant validatedAt,
        boolean checkoutReady,
        List<CartValidationIssueResponse> cartIssues,
        List<Line> lines
) {
    public record Line(
            CartStoredItem stored,
            ProductCommerceClient.ProductContext product,
            Integer availableQuantity,
            List<CartValidationIssueResponse> issues
    ) {
    }
}
