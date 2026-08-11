package com.msb.ecom.order_service.model;

import java.time.Instant;

public final class BusinessFulfillmentView {

    private BusinessFulfillmentView() {
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
}
