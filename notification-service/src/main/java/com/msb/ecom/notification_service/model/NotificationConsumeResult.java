package com.msb.ecom.notification_service.model;

public record NotificationConsumeResult(
        Outcome outcome,
        String safeCode
) {

    public enum Outcome {
        CREATED,
        REPLAYED,
        REJECTED,
        CONFLICT,
        POISON,
        RETRY_REQUIRED,
        DISABLED
    }
}
