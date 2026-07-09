package com.msb.ecom.chat_service.model;

public class ChatDependencyUnavailableException extends RuntimeException {

    public ChatDependencyUnavailableException(String message) {
        super(message);
    }
}
