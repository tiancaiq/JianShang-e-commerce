package com.msb.ecom.auth_service.dto;

import java.util.List;

public record BusinessMembershipResponse(
        String businessId,
        String userId,
        String role,
        String status,
        List<String> permissions
) {
}
