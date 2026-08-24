package com.msb.ecom.product_service.dto;

import java.time.Instant;
import java.util.List;

public record InternalAppealListingContext(
        String enforcementActionId,
        String listingId,
        String safeListingLabel,
        String sellerType,
        String individualSellerUserId,
        String businessId,
        String listingStatus,
        long listingVersion,
        String actionType,
        List<String> scopes,
        String lifecycleState,
        Instant effectiveAt,
        Instant expiresAt,
        long enforcementVersion,
        Instant createdAt,
        String reasonCode,
        String reason,
        String caseId,
        String currentEffectiveEnforcementState
) { }
