package com.msb.ecom.chat_service.dto;

public record ChatParticipantSummary(
        String participantId,
        String displayName,
        String avatarUrl,
        String initials,
        String roleInConversation,
        boolean currentUser
) {
}
