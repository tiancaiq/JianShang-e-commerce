package com.msb.ecom.auth_service.dto;

import com.msb.ecom.auth_service.model.BusinessApplication;

import java.time.Instant;

public record BusinessApplicationResponse(
        String id,
        String applicantUserId,
        String legalName,
        String businessType,
        String country,
        String contactEmail,
        String contactPhone,
        String publicCity,
        String publicRegion,
        String websiteUrl,
        String description,
        String status,
        Instant submittedAt,
        String reviewerUserId,
        String approvedBusinessId,
        String decisionReason,
        Instant decidedAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static BusinessApplicationResponse from(BusinessApplication application) {
        return new BusinessApplicationResponse(
                application.getId(),
                application.getApplicantUserId(),
                application.getLegalName(),
                application.getBusinessType(),
                application.getCountry(),
                application.getContactEmail(),
                application.getContactPhone(),
                application.getPublicCity(),
                application.getPublicRegion(),
                application.getWebsiteUrl(),
                application.getDescription(),
                application.getStatus(),
                application.getSubmittedAt(),
                application.getReviewerUserId(),
                application.getApprovedBusinessId(),
                application.getDecisionReason(),
                application.getDecidedAt(),
                application.getVersion(),
                application.getCreatedAt(),
                application.getUpdatedAt());
    }
}
