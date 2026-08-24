package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminFinanceOrderContext(
        String paymentIntentId,
        String orderId,
        String orderNumber,
        String buyerUserId,
        BigDecimal totalAmount,
        String currency,
        Instant createdAt,
        List<Business> businesses,
        List<Item> items,
        List<DisputeRecommendation> disputes) {
    public AdminFinanceOrderContext {
        businesses = businesses == null ? List.of() : List.copyOf(businesses);
        items = items == null ? List.of() : List.copyOf(items);
        disputes = disputes == null ? List.of() : List.copyOf(disputes);
    }

    public record Business(String businessId, String storeNameAtPurchase, BigDecimal totalAmount) { }
    public record Item(String listingId, String titleAtPurchase, int quantity, BigDecimal lineTotal) { }
    public record DisputeRecommendation(String disputeId, String businessOrderId, String businessId,
            String resolutionType, BigDecimal recommendedRefundAmount, String currency,
            String resolutionReason, Instant resolvedAt) { }
}
