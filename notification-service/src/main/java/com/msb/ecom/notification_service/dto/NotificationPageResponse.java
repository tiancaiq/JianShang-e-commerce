package com.msb.ecom.notification_service.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record NotificationPageResponse(
        List<NotificationItem> items,
        PageMetadata page
) {

    public NotificationPageResponse {
        items = List.copyOf(items);
    }

    public record NotificationItem(
            String id,
            String type,
            String messageKey,
            Map<String, String> presentationArgs,
            String safeRoute,
            boolean read,
            Instant readAt,
            Instant createdAt
    ) {

        public NotificationItem {
            presentationArgs = Map.copyOf(presentationArgs);
        }
    }

    public record PageMetadata(
            String nextCursor,
            boolean hasMore
    ) {
    }
}
