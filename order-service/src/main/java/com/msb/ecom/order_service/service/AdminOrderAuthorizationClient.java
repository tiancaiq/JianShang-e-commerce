package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.order_service.security.AdminOrderPermission;

import java.util.List;
import java.util.Set;

public interface AdminOrderAuthorizationClient {
    Access requireAdmin(String bearerToken);

    Labels labels(String bearerToken, Set<String> userIds, Set<String> businessIds);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Access(String userId, List<String> roles, List<String> permissions, String accountState) {
        public Access {
            roles = roles == null ? List.of() : List.copyOf(roles);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }

        public boolean has(AdminOrderPermission permission) {
            return permissions.contains(permission.id());
        }

        public boolean has(String permission) {
            return permissions.contains(permission);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Labels(List<UserLabel> users, List<BusinessLabel> businesses, List<EnforcementLabel> enforcements) {
        public Labels {
            users = users == null ? List.of() : List.copyOf(users);
            businesses = businesses == null ? List.of() : List.copyOf(businesses);
            enforcements = enforcements == null ? List.of() : List.copyOf(enforcements);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserLabel(String id, String displayName, String avatarUrl) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessLabel(
            String id,
            String legalName,
            String storeId,
            String storeSlug,
            String storeName,
            String publicCity,
            String publicRegion,
            boolean verified) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnforcementLabel(
            String enforcementActionId,
            String targetType,
            String targetId,
            String actionType,
            Set<String> scopes) {
        public EnforcementLabel {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
