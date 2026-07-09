package com.msb.ecom.chat_service.dto;

public record ChatListingSummary(
        String id,
        String title,
        String sellerType,
        Integer quantity,
        String publicCity,
        String publicRegion,
        String thumbnailUrl,
        String transactionNotice
) {
}
