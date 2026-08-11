package com.msb.ecom.order_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record BusinessOrderFulfillmentResponse(
        String businessOrderId,
        String fulfillmentStatus,
        long version,
        Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Shipment shipment
) {
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
    ) {
    }
}
