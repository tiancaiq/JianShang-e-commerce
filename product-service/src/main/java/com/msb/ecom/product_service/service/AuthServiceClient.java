package com.msb.ecom.product_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Set;

public interface AuthServiceClient {

    IndividualSellerAuthorization requireActiveIndividualSeller(String bearerToken);

    CurrentUser requireCurrentUser(String bearerToken);

    BusinessMembershipAuthorization requireBusinessListingPermission(String bearerToken, String businessId);

    BusinessStoreContextAuthorization requireBusinessStoreContext(String bearerToken, String businessId);

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
            String role
    ) {
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
