package com.msb.ecom.chat_service.dto;

public record ChatParticipantSummary(
        String participantId,
        String displayName,
        String publicHandle,
        String avatarUrl,
        String initials,
        String roleInConversation,
        boolean currentUser
) {
}
