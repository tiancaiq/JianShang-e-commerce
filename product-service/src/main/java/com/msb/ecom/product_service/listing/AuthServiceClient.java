package com.msb.ecom.product_service.listing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public interface AuthServiceClient {

    IndividualSellerAuthorization requireActiveIndividualSeller(String bearerToken);

    BusinessMembershipAuthorization requireBusinessListingPermission(String bearerToken, String businessId);

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
}
