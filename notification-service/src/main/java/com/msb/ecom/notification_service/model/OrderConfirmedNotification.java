package com.msb.ecom.notification_service.model;

import java.time.Instant;
import java.util.List;

public record OrderConfirmedNotification(
        String orderId,
        String checkoutId,
        String paymentIntentId,
        String status,
        List<String> businessIds,
        Instant confirmedAt,
        String recipientUserId
) {
}
