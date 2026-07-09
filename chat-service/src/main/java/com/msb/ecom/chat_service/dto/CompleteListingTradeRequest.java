package com.msb.ecom.chat_service.dto;

public record CompleteListingTradeRequest(
        String conversationId,
        String sellerUserId,
        String buyerUserId,
        Integer quantitySold
) {
}
