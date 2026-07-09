package com.msb.ecom.chat_service.model;

public class ChatCompletionNotAllowedException extends RuntimeException {
    public ChatCompletionNotAllowedException(String message) {
        super(message);
    }
}
