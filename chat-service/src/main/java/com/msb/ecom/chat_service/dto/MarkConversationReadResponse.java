package com.msb.ecom.chat_service.dto;

import java.time.Instant;

public record MarkConversationReadResponse(
        String conversationId,
        String lastReadMessageId,
        Instant lastReadAt,
        boolean unread
) {
}
