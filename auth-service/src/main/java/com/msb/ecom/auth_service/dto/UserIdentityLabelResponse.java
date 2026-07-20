package com.msb.ecom.auth_service.dto;

public record UserIdentityLabelResponse(
        String id,
        String displayName,
        String publicHandle,
        String avatarUrl
) {
}
