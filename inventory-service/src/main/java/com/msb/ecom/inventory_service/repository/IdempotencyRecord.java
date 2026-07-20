package com.msb.ecom.inventory_service.repository;

public record IdempotencyRecord(
        String requestHash,
        int httpStatus,
        String responseJson
) {
}
