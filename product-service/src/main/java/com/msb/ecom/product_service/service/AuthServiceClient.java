package com.msb.ecom.product_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Set;

public interface AuthServiceClient {

    IndividualSellerAuthorization requireActiveIndividualSeller(String bearerToken);

    BusinessMembershipAuthorization requireBusinessListingPermission(String bearerToken, String businessId);

    PlatformAdminAuthorization requirePlatformAdmin(String bearerToken);

    AdminIdentityLabels lookupAdminIdentityLabels(String bearerToken, Set<String> userIds, Set<String> businessIds);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IndividualSellerAuthorization(
            String userId,
            String publicCity,
            String publicRegion,
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
            String displayName
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BusinessIdentityLabel(
            String id,
            String legalName
    ) {
    }
}
