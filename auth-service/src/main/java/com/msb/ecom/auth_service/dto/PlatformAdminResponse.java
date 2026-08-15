package com.msb.ecom.auth_service.dto;

import java.util.List;

public record PlatformAdminResponse(
        String userId,
        String role,
        List<String> roles,
        List<String> permissions,
        String accountState
) {
}
