package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BusinessOrderReturnResponse(
        boolean eligible,
        String ineligibilityCode,
        String returnId,
        String orderId,
        String businessOrderId,
        String storeName,
        String reasonCode,
        String buyerComment,
        String policyVersion,
        Instant requestedAt,
        Instant windowExpiresAt,
        String status,
        String refundStatus,
        String inventoryDisposition,
        Instant receivedAt,
        String refundId,
        BigDecimal refundAmount,
        String currency,
        Instant completedAt,
        long version,
        DemoShipment shipment,
        List<TimelineEntry> timeline) {

    public record DemoShipment(String carrierDisplayName, String trackingReference,
            Instant createdAt, Instant inTransitAt, String disclosure) {}
    public record TimelineEntry(String status, Instant occurredAt) {}
}
