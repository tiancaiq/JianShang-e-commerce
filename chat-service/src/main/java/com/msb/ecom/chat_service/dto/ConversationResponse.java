package com.msb.ecom.chat_service.dto;

import java.time.Instant;
import java.util.List;

public record ConversationResponse(
        String id,
        String conversationType,
        String status,
        ChatListingSummary listing,
        List<ChatParticipantSummary> participants,
        ConversationCompletionResponse completion,
        Instant createdAt,
        Instant updatedAt
) {
}
