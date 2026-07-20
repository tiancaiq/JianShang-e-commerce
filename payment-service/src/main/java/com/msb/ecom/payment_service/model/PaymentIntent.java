package com.msb.ecom.payment_service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record PaymentIntent(
        String id,
        String checkoutId,
        long checkoutVersion,
        String checkoutSnapshotHash,
        String buyerId,
        String callerScope,
        List<String> businessIds,
        BigDecimal amount,
        String currency,
        String paymentMethodType,
        String captureMethod,
        String merchantOfRecord,
        String fundsFlow,
        String provider,
        String providerReference,
        String providerActionType,
        PaymentIntentStatus status,
        long version,
        Instant expiresAt,
        String safeErrorCode,
        String safeErrorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public PaymentIntent {
        businessIds = List.copyOf(businessIds);
        amount = amount.setScale(4);
        Objects.requireNonNull(status, "status");
    }

    // Enforces the stable provider-neutral lifecycle before persistence changes state.
    public PaymentIntent transition(
            PaymentIntentStatus target,
            String nextProviderReference,
            String nextActionType,
            String errorCode,
            String errorMessage,
            Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Payment intent transition is not allowed.");
        }
        return new PaymentIntent(
                id, checkoutId, checkoutVersion, checkoutSnapshotHash, buyerId, callerScope,
                businessIds, amount, currency, paymentMethodType, captureMethod, merchantOfRecord,
                fundsFlow, provider, nextProviderReference, nextActionType,
                target, version + 1, expiresAt, errorCode, errorMessage, createdAt, now);
    }
}
