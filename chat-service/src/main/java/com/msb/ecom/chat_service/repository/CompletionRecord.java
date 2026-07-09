package com.msb.ecom.chat_service.repository;

import java.time.Instant;

public record CompletionRecord(
        String id,
        String listingId,
        String conversationId,
        String sellerUserId,
        String buyerUserId,
        int quantitySold,
        String status,
        Instant sellerMarkedDoneAt,
        Instant buyerConfirmedAt,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt
) {
}
