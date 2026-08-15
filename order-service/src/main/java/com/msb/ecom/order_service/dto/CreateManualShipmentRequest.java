package com.msb.ecom.order_service.dto;

import java.time.Instant;

public record CreateManualShipmentRequest(
        String carrierDisplayName,
        String serviceDisplayName,
        String trackingNumber,
        Instant shippedAt
) {
}
