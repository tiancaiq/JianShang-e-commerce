package com.msb.ecom.order_service.repository;

public record CheckoutIdempotencyRecord(
        String callerScope,
        String idempotencyKey,
        String requestHash,
        String operation,
        String state,
        String checkoutId,
        Integer httpStatus,
        String responseJson
) {
}
