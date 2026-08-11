package com.msb.ecom.order_service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BuyerOrderView(
        String orderId,
        String status,
        String paymentStatus,
        BigDecimal totalAmount,
        String currency,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<Group> groups,
        Address shippingAddress
) {

    public record Group(
            String businessOrderId,
            String businessId,
            String storeId,
            String storeName,
            String status,
            BigDecimal totalAmount,
            List<Item> items,
            long version,
            List<BusinessFulfillmentView.TimelineEntry> timeline,
            BusinessFulfillmentView.Shipment shipment
    ) {
        public Group(
                String businessOrderId,
                String businessId,
                String storeId,
                String status,
                BigDecimal totalAmount,
                List<Item> items) {
            this(businessOrderId, businessId, storeId, null, status, totalAmount, items,
                    0, List.of(), null);
        }

        public Group(
                String businessOrderId,
                String businessId,
                String storeId,
                String storeName,
                String status,
                BigDecimal totalAmount,
                List<Item> items) {
            this(businessOrderId, businessId, storeId, storeName, status, totalAmount,
                    items, 0, List.of(), null);
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

    public record Address(
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
