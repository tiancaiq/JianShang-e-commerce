package com.msb.ecom.payment_service.repository;

public record PaymentIdempotencyRecord(
        String callerScope,
        String idempotencyKey,
        String requestHash,
        String state,
        String paymentIntentId,
        Integer httpStatus
) {
}
