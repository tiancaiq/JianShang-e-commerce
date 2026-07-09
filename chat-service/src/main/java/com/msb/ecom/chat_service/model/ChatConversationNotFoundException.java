package com.msb.ecom.chat_service.model;

public class ChatConversationNotFoundException extends RuntimeException {
    public ChatConversationNotFoundException() {
        super("Conversation was not found.");
    }
}
