package com.msb.ecom.notification_service.model;

public record NotificationSourceRecord(
        String payloadHash,
        String state,
        String outcome,
        String safeErrorCode
) {
}
