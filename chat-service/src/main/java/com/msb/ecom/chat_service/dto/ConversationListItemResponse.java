package com.msb.ecom.chat_service.dto;

import java.time.Instant;

public record ConversationListItemResponse(
        String id,
        String conversationType,
        String status,
        ChatListingSummary listing,
        ChatParticipantSummary otherParticipant,
        MessageResponse lastMessage,
        boolean unread,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt
) {
}
