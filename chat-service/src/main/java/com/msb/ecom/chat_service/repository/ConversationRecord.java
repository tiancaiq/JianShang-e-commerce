package com.msb.ecom.chat_service.repository;

import java.time.Instant;

public record ConversationRecord(
        String id,
        String conversationType,
        String subjectListingId,
        String buyerUserId,
        String sellerUserId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
