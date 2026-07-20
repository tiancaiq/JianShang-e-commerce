package com.msb.ecom.payment_service.model;

import java.time.Instant;

public record FakePaymentWebhookEvent(
        String eventId,
        Type type,
        Instant occurredAt,
        String providerReference,
        String failureCode
) {
    public enum Type {
        SUCCEEDED("payment_intent.succeeded", PaymentIntentStatus.SUCCEEDED),
        FAILED("payment_intent.failed", PaymentIntentStatus.FAILED);

        private final String wireName;
        private final PaymentIntentStatus targetStatus;

        Type(String wireName, PaymentIntentStatus targetStatus) {
            this.wireName = wireName;
            this.targetStatus = targetStatus;
        }

        public String wireName() {
            return wireName;
        }

        public PaymentIntentStatus targetStatus() {
            return targetStatus;
        }

        public static Type fromWireName(String value) {
            for (Type type : values()) {
                if (type.wireName.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unsupported payment webhook event type.");
        }
    }
}
