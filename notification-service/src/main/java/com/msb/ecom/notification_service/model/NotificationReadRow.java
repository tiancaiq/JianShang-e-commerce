package com.msb.ecom.notification_service.model;

import java.time.Instant;

public record NotificationReadRow(
        String id,
        String type,
        String messageKey,
        String messageArgsJson,
        String route,
        Instant readAt,
        Instant createdAt
) {
}
