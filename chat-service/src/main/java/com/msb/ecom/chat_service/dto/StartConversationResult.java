package com.msb.ecom.chat_service.dto;

public record StartConversationResult(
        ConversationResponse response,
        boolean created
) {
}
