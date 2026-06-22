package com.msb.ecom.auth_service.dto;

import com.msb.ecom.auth_service.model.IndividualSellerProfile;

import java.time.Instant;

public record IndividualSellerProfileResponse(
        String id,
        String userId,
        String publicCity,
        String publicRegion,
        String status,
        long completedSalesCount,
        String termsVersion,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static IndividualSellerProfileResponse from(IndividualSellerProfile profile) {
        return new IndividualSellerProfileResponse(
                profile.getId(),
                profile.getUserId(),
                profile.getPublicCity(),
                profile.getPublicRegion(),
                profile.getStatus(),
                profile.getCompletedSalesCount(),
                profile.getTermsVersion(),
                profile.getVersion(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
