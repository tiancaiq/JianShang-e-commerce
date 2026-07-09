package com.msb.ecom.chat_service.model;

public class ChatMessageValidationException extends RuntimeException {
    public ChatMessageValidationException(String message) {
        super(message);
    }
}
