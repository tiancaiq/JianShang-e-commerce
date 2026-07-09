package com.msb.ecom.product_service.dto;

public record ChatTradeCompletionRequest(
        String conversationId,
        String sellerUserId,
        String buyerUserId,
        Integer quantitySold
) {
}
