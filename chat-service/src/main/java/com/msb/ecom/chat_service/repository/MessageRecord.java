package com.msb.ecom.chat_service.repository;

import java.time.Instant;

public record MessageRecord(
        String id,
        String conversationId,
        String senderUserId,
        String messageType,
        String body,
        String moderationState,
        Instant createdAt
) {
}
