package com.msb.ecom.auth_service.dto;

import com.msb.ecom.auth_service.model.User;

import java.time.Instant;

public record CurrentUserResponse(
        String id,
        String keycloakSub,
        String email,
        boolean emailVerified,
        String displayName,
        String publicHandle,
        String phone,
        boolean phoneVerified,
        String avatarUrl,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(
                user.getId(),
                user.getKeycloakSub(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getDisplayName(),
                user.getPublicHandle(),
                user.getPhone(),
                user.isPhoneVerified(),
                user.getAvatarUrl(),
                user.getStatus(),
                user.getVersion(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
