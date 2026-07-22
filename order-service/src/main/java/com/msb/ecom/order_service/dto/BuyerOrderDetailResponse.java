package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BuyerOrderDetailResponse(
        String orderId,
        String status,
        String paymentStatus,
        BigDecimal totalAmount,
        String currency,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<Group> groups,
        ShippingAddress shippingAddress
) {

    public record Group(
            String businessOrderId,
            String businessId,
            String storeId,
            String status,
            BigDecimal totalAmount,
            String currency,
            List<Item> items
    ) {
    }

    public record Item(
            String listingId,
            String title,
            String businessId,
            String storeId,
            BigDecimal unitPrice,
            String currency,
            int quantity,
            BigDecimal lineTotal,
            String policyVersion
    ) {
    }

    public record ShippingAddress(
            String label,
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
