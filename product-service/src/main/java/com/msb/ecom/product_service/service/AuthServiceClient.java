package com.msb.ecom.product_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.product_service.security.AdminPermission;

import java.util.List;
import java.util.Set;

public interface AuthServiceClient {

    IndividualSellerAuthorization requireActiveIndividualSeller(String bearerToken);

    CurrentUser requireCurrentUser(String bearerToken);

    // Resolves REP-01A actors while preserving authentication versus Auth dependency failures.
    CurrentUser requireCurrentUserForReport(String bearerToken);

    BusinessMembershipAuthorization requireBusinessListingPermission(String bearerToken, String businessId);

    // Distinguishes authoritative non-editable membership from authentication and dependency failures.
    BusinessListingPermissionDecision checkBusinessListingPermission(String bearerToken, String businessId);

    BusinessStoreContextAuthorization requireBusinessStoreContext(String bearerToken, String businessId);

    void requireUserCapability(String userId, String scope);

    void requireBusinessCapability(String businessId, String scope);

    PlatformAdminAuthorization requirePlatformAdmin(String bearerToken);

    AdminIdentityLabels lookupAdminIdentityLabels(String bearerToken, Set<String> userIds, Set<String> businessIds);

    AdminIdentityLabels lookupPublicSellerLabels(Set<String> userIds, Set<String> businessIds);

    List<PublicBusinessStoreSearchResult> searchPublicBusinessStores(
            String query,
            Set<String> businessIds,
            Set<String> storeIds);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndividualSellerAuthorization(
            String userId,
            String publicCity,
            String publicRegion,
            String status
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CurrentUser(
            String id,
            String status
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessMembershipAuthorization(
            String businessId,
            String userId,
            String role,
            String status,
            List<String> permissions
    ) {
    }

    enum BusinessListingPermissionDecision {
        EDITABLE,
        NOT_EDITABLE
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserCapabilityDecision(
            String scope,
            boolean allowed,
            String effectiveAction,
            String enforcementActionId,
            String effectiveAt,
            String expiresAt,
            String supportReference) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserCapabilityDecisionResponse(
            String userId,
            String evaluatedAt,
            List<UserCapabilityDecision> decisions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessCapabilityDecision(
            String scope,
            boolean allowed,
            String effectiveAction,
            String enforcementActionId,
            String effectiveAt,
            String expiresAt,
            String supportReference) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessCapabilityDecisionResponse(
            String businessId,
            String evaluatedAt,
            List<BusinessCapabilityDecision> decisions) {
    }

    final class AuthenticationException extends RuntimeException {
        public AuthenticationException() {
            super("Authenticated user is required.");
        }
    }

    final class DependencyUnavailableException extends RuntimeException {
        public DependencyUnavailableException() {
            super("Auth service is unavailable.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessStoreContextAuthorization(
            String businessId,
            String businessLegalName,
            String businessStatus,
            String membershipRole,
            List<String> permissions,
            BusinessStoreAuthorization store
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessStoreAuthorization(
            String id,
            String businessId,
            String slug,
            String name,
            String status
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlatformAdminAuthorization(
            String userId,
            String role,
            List<String> roles,
            List<String> permissions,
            String accountState
    ) {
        public PlatformAdminAuthorization(String userId, String role) {
            this(
                    userId,
                    role,
                    List.of("SUPER_ADMIN"),
                    List.of(
                            AdminPermission.DASHBOARD_READ.id(),
                            AdminPermission.AUDIT_READ.id(),
                            AdminPermission.LISTING_MODERATION_READ.id(),
                            AdminPermission.LISTING_MODERATION_CLAIM.id(),
                            AdminPermission.LISTING_MODERATION_RESOLVE.id(),
                            AdminPermission.LISTING_EDIT.id(),
                            AdminPermission.LISTING_REMOVE.id(),
                            AdminPermission.LISTING_SUSPEND.id(),
                            AdminPermission.LISTING_REINSTATE.id(),
                            AdminPermission.CATALOG_READ.id(),
                            AdminPermission.CATALOG_CATEGORY_MANAGE.id(),
                            AdminPermission.CATALOG_ATTRIBUTE_MANAGE.id(),
                            AdminPermission.CATALOG_POLICY_MANAGE.id()),
                    "ACTIVE");
        }

        public PlatformAdminAuthorization {
            roles = roles == null ? List.of("SUPER_ADMIN") : List.copyOf(roles);
            permissions = permissions == null
                    ? new PlatformAdminAuthorization(userId, role).permissions()
                    : List.copyOf(permissions);
            accountState = accountState == null ? "ACTIVE" : accountState;
        }

        public boolean hasPermission(AdminPermission permission) {
            return permissions.contains(permission.id());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdminIdentityLabels(
            List<UserIdentityLabel> users,
            List<BusinessIdentityLabel> businesses
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserIdentityLabel(
            String id,
            String displayName,
            String avatarUrl
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessIdentityLabel(
            String id,
            String legalName,
            String storeId,
            String storeSlug,
            String storeName,
            String publicCity,
            String publicRegion,
            boolean verified
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublicBusinessStoreSearchResult(
            String businessId,
            String storeId,
            String storeName,
            String businessLegalName
    ) {
    }
}
