package com.msb.ecom.auth_service.dto;

import java.util.List;
import java.util.Set;

public record AdminOrderIdentityContextResponse(
        List<UserIdentityLabelResponse> users,
        List<BusinessIdentityLabelResponse> businesses,
        List<EnforcementSummary> enforcements) {

    public AdminOrderIdentityContextResponse {
        users = users == null ? List.of() : List.copyOf(users);
        businesses = businesses == null ? List.of() : List.copyOf(businesses);
        enforcements = enforcements == null ? List.of() : List.copyOf(enforcements);
    }

    public record EnforcementSummary(
            String enforcementActionId,
            String targetType,
            String targetId,
            String actionType,
            Set<String> scopes) {
        public EnforcementSummary {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
