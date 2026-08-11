package com.msb.ecom.payment_service.webhook;

import java.time.Instant;

public record VerifiedPaymentProviderEvent(
        String provider,
        String eventId,
        String eventType,
        Kind kind,
        String providerObjectReference,
        String relatedPaymentReference,
        String providerStatus,
        String safeFailureCode,
        Instant signedAt,
        Instant occurredAt) {

    public enum Kind {
        PAYMENT_ACTION_REQUIRED,
        PAYMENT_PROCESSING,
        PAYMENT_SUCCEEDED,
        PAYMENT_FAILED,
        REFUND_PROCESSING,
        REFUND_SUCCEEDED,
        REFUND_FAILED,
        UNSUPPORTED
    }
}
