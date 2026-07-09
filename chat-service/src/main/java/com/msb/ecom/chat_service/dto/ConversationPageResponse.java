package com.msb.ecom.chat_service.dto;

import java.util.List;

public record ConversationPageResponse(
        List<ConversationListItemResponse> items,
        String nextCursor
) {
}
