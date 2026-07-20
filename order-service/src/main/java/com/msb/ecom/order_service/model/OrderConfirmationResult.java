package com.msb.ecom.order_service.model;

public record OrderConfirmationResult(
        Outcome outcome,
        String orderId,
        String safeCode
) {
    public enum Outcome {
        CONFIRMED,
        REPLAYED,
        IN_PROGRESS,
        RETRY_REQUIRED,
        REJECTED
    }
}
