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
        ShippingAddress shippingAddress,
        Cancellation cancellation
) {

    public BuyerOrderDetailResponse(
            String orderId,
            String status,
            String paymentStatus,
            BigDecimal totalAmount,
            String currency,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<Group> groups,
            ShippingAddress shippingAddress) {
        this(orderId, status, paymentStatus, totalAmount, currency, version,
                createdAt, updatedAt, groups, shippingAddress, null);
    }

    public record Group(
            String businessOrderId,
            String businessId,
            String storeId,
            String storeName,
            String status,
            BigDecimal totalAmount,
            String currency,
            List<Item> items,
            long version,
            List<TimelineEntry> timeline,
            Shipment shipment
    ) {
        public Group(
                String businessOrderId,
                String businessId,
                String storeId,
                String storeName,
                String status,
                BigDecimal totalAmount,
                String currency,
                List<Item> items) {
            this(businessOrderId, businessId, storeId, storeName, status,
                    totalAmount, currency, items, 0, List.of(), null);
        }

        public Group(
                String businessOrderId,
                String businessId,
                String storeId,
                String status,
                BigDecimal totalAmount,
                String currency,
                List<Item> items) {
            this(businessOrderId, businessId, storeId, null, status, totalAmount,
                    currency, items, 0, List.of(), null);
        }
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

    public record TimelineEntry(String status, Instant occurredAt) {}

    public record Shipment(
            String shipmentId,
            String source,
            String carrierDisplayName,
            String serviceDisplayName,
            String trackingNumber,
            String status,
            long version,
            Instant shippedAt,
            Instant deliveredAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record Cancellation(
            boolean eligible,
            String ineligibilityCode,
            String requestId,
            String requestStatus,
            String reasonCode,
            Instant requestedAt,
            Instant decidedAt,
            Instant completedAt,
            String inventoryStatus,
            Refund refund
    ) {
    }

    public record Refund(
            String status,
            String refundId,
            BigDecimal amount,
            String currency,
            String displayName,
            String disclosure
    ) {
    }
}
