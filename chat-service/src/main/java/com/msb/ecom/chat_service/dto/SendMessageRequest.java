package com.msb.ecom.chat_service.dto;

public record SendMessageRequest(
        String messageType,
        String body
) {
}
