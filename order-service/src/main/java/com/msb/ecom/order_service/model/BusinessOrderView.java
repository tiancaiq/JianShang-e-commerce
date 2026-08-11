package com.msb.ecom.order_service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BusinessOrderView(
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
        BigDecimal platformFeeProjection,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt,
        List<Item> items,
        Address shippingAddress,
        long version,
        List<BusinessFulfillmentView.TimelineEntry> timeline,
        BusinessFulfillmentView.Shipment shipment
) {

    public BusinessOrderView(
            String businessOrderId, String sellerOrderNumber, String businessId,
            String storeId, String status, String cancellationStatus, String buyerOrderId,
            String buyerOrderNumber, String paymentStatus, int itemCount, int totalQuantity,
            BigDecimal subtotal, BigDecimal totalAmount, String currency,
            BigDecimal platformFeeProjection, Instant confirmedAt, Instant createdAt,
            Instant updatedAt, List<Item> items, Address shippingAddress) {
        this(businessOrderId, sellerOrderNumber, businessId, storeId, status,
                cancellationStatus, buyerOrderId, buyerOrderNumber, paymentStatus,
                itemCount, totalQuantity, subtotal, totalAmount, currency,
                platformFeeProjection, confirmedAt, createdAt, updatedAt, items,
                shippingAddress, 0, List.of(), null);
    }

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

    public record Address(
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
