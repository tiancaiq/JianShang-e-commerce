package com.msb.ecom.chat_service.dto;

import java.time.Instant;

public record ConversationCompletionResponse(
        String id,
        String listingId,
        String conversationId,
        String sellerUserId,
        String buyerUserId,
        Integer quantitySold,
        String status,
        Instant sellerMarkedDoneAt,
        Instant buyerConfirmedAt,
        Instant cancelledAt,
        boolean currentUserCanMarkDone,
        boolean currentUserCanConfirm
) {
}
