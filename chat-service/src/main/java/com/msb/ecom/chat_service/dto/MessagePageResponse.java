package com.msb.ecom.chat_service.dto;

import java.util.List;

public record MessagePageResponse(
        List<MessageResponse> items,
        String nextCursor
) {
}
