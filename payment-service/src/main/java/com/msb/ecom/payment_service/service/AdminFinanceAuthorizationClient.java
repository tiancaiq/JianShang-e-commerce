package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Set;

public interface AdminFinanceAuthorizationClient {
    Access requireAdmin(String bearerToken);
    Labels labels(String bearerToken, Set<String> userIds, Set<String> businessIds);

    record Access(String userId, List<String> roles, List<String> permissions, String accountState) {
        public Access { roles = roles == null ? List.of() : List.copyOf(roles); permissions = permissions == null ? List.of() : List.copyOf(permissions); }
        public boolean has(String permission) { return permissions.contains(permission); }
    }
    record Labels(List<UserLabel> users, List<BusinessLabel> businesses) {
        public Labels { users = users == null ? List.of() : List.copyOf(users); businesses = businesses == null ? List.of() : List.copyOf(businesses); }
    }
    @JsonIgnoreProperties(ignoreUnknown=true) record UserLabel(String id,String displayName,String avatarUrl) { }
    @JsonIgnoreProperties(ignoreUnknown=true) record BusinessLabel(String id,String legalName,String storeId,String storeSlug,String storeName) { }
}
