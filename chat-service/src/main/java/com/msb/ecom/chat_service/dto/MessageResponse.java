package com.msb.ecom.chat_service.dto;

import java.time.Instant;

public record MessageResponse(
        String id,
        String conversationId,
        String senderUserId,
        String messageType,
        String body,
        String moderationState,
        boolean currentUser,
        Instant createdAt
) {
}
