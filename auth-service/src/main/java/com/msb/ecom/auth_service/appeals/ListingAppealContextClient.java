package com.msb.ecom.auth_service.appeals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface ListingAppealContextClient {
    Optional<ListingEnforcementContext> byActionId(String actionId);
    List<ListingEnforcementContext> activeForActor(String userId, Set<String> businessIds);
    ListingResolutionResult previewResolution(String appealId, ListingResolutionRequest request);
    ListingResolutionResult executeResolution(String appealId, ListingResolutionRequest request);

    record ListingEnforcementContext(
            String enforcementActionId, String listingId, String safeListingLabel, String sellerType,
            String individualSellerUserId, String businessId, String listingStatus, long listingVersion,
            String actionType, List<String> scopes, String lifecycleState, Instant effectiveAt,
            Instant expiresAt, long enforcementVersion, Instant createdAt, String reasonCode,
            String reason, String caseId, String currentEffectiveEnforcementState) { }

    record ListingResolutionRequest(
            String outcome, String enforcementActionId, long expectedEnforcementVersion,
            long expectedTargetVersion, String reasonCode, String reason,
            String idempotencyKey, ListingReplacement replacement, ListingExecutor executor,
            Map<String, String> safeMetadata, boolean confirmed, String confirmationToken,
            boolean recoveryOnly) { }
    record ListingReplacement(String actionType, List<String> scopes, Instant effectiveAt,
                              Instant expiresAt, String reasonCode, String reason) { }
    record ListingExecutor(String actorId, String displayName, List<String> permissions) { }
    record ListingEffectiveRestriction(String scope, String actionType, String enforcementActionId) { }
    record ListingResolutionResult(
            String appealId, String outcome, String originalEnforcementActionId,
            long originalEnforcementVersion, String replacementEnforcementActionId,
            Long replacementEnforcementVersion, List<ListingEffectiveRestriction> effectiveRestrictions,
            long listingVersion, boolean dryRun, String correlationId, boolean replayed,
            String currentEffectiveEnforcementState, List<String> warnings,
            List<String> impactSummary, String confirmationToken) { }
}
