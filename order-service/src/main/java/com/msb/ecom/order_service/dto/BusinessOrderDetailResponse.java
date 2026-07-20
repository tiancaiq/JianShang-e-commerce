package com.msb.ecom.order_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BusinessOrderDetailResponse(
        String businessOrderId,
        String sellerOrderNumber,
        String businessId,
        String storeId,
        String status,
        String cancellationStatus,
        String buyerOrderId,
        String buyerOrderNumber,
        String paymentStatus,
        int itemCount,
        int totalQuantity,
        BigDecimal subtotal,
        BigDecimal totalAmount,
        String currency,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal platformFeeProjection,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt,
        List<Item> items,
        ShippingAddress shippingAddress
) {

    public record Item(
            String listingId,
            String title,
            String sku,
            String itemCondition,
            String thumbnailUrl,
            BigDecimal unitPrice,
            String currency,
            int quantity,
            BigDecimal lineTotal,
            String policyVersion
    ) {
    }

    public record ShippingAddress(
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode
    ) {
    }
}
