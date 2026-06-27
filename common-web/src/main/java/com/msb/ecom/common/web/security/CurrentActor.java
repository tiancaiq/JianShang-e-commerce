package com.msb.ecom.common.web.security;

public record CurrentActor(
        String subject,
        String accessToken,
        String email,
        String displayName,
        boolean emailVerified
) {
}
