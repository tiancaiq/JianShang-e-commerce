package com.msb.ecom.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentRefundResponse(
        String refundId,
        String paymentIntentId,
        String orderId,
        String cancellationRequestId,
        BigDecimal amount,
        String currency,
        String provider,
        String providerReference,
        String status,
        Instant completedAt,
        String displayName,
        String disclosure
) {
}
