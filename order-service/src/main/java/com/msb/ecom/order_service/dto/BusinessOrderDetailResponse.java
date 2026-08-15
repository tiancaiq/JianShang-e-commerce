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
        ShippingAddress shippingAddress,
        long version,
        List<TimelineEntry> timeline,
        @JsonInclude(JsonInclude.Include.NON_NULL) Shipment shipment
) {

    public BusinessOrderDetailResponse(
            String businessOrderId, String sellerOrderNumber, String businessId,
            String storeId, String status, String cancellationStatus, String buyerOrderId,
            String buyerOrderNumber, String paymentStatus, int itemCount, int totalQuantity,
            BigDecimal subtotal, BigDecimal totalAmount, String currency,
            BigDecimal platformFeeProjection, Instant confirmedAt, Instant createdAt,
            Instant updatedAt, List<Item> items, ShippingAddress shippingAddress) {
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
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant deliveredAt,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
