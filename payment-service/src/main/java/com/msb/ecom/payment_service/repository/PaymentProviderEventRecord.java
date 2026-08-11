package com.msb.ecom.payment_service.repository;

import com.msb.ecom.payment_service.model.PaymentWebhookOutcome;

public record PaymentProviderEventRecord(
        String provider,
        String providerEventId,
        String eventType,
        String providerReference,
        String paymentIntentId,
        String refundId,
        String payloadHash,
        PaymentWebhookOutcome outcome,
        String resultingStatus
) {
}
