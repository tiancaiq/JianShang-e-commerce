package com.msb.ecom.chat_service.repository;

import java.time.Instant;

public record ConversationListRecord(
        ConversationRecord conversation,
        String currentUserRole,
        String lastReadMessageId,
        Instant lastReadAt,
        MessageRecord lastMessage
) {
}
