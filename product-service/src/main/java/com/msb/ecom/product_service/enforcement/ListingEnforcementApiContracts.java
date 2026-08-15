package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TimelineEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ListingEnforcementApiContracts {
    private ListingEnforcementApiContracts() { }

    public record CreateRequest(ActionType actionType, Set<Scope> scopes, String reasonCode, String reason,
            Instant effectiveAt, Instant expiresAt, Long expectedListingVersion, String idempotencyKey,
            Map<String, String> safeMetadata) { }

    public record RevokeRequest(Long expectedEnforcementVersion, String reasonCode, String reason,
            String idempotencyKey, Map<String, String> safeMetadata) { }

    public record ActionView(String enforcementActionId, ActionType actionType, Set<Scope> scopes,
            String lifecycleState, Instant effectiveAt, Instant expiresAt, long version, Instant createdAt,
            Instant revokedAt, String reasonCode, String reason, String actorId, String actorDisplayName,
            String caseId) { }

    public record AdminCapabilities(boolean canRead, boolean canSuspend, boolean canReinstate,
            boolean removedByAdmin, String readOnlyReason, Set<Scope> operationalScopes) { }

    public record Detail(String listingId, String listingStatus, String moderationStatus, long listingVersion,
            boolean publicVisibilityAllowed, boolean purchasabilityAllowed, ActionType strongestActiveAction,
            List<EffectiveRestriction> effectiveRestrictions, List<ActionView> activeEnforcementActions,
            List<ActionView> historicalEnforcementActions, AdminCapabilities availableAdminCapabilities) { }

    public record Preview(Result proposedAction, List<ActionView> overlappingActions,
            List<EffectiveRestriction> effectiveRestrictionsAfter, boolean predictedPublicVisibility,
            boolean predictedPurchasability, boolean targetVersionCurrent, List<String> impactSummary,
            List<String> warnings) { }

    public record EvaluateBatchRequest(Set<String> listingIds, Set<Scope> scopes) { }

    public record CapabilityDecision(Scope scope, boolean allowed, ActionType effectiveAction,
            String enforcementActionId, Instant expiresAt, String supportReference) { }

    public record ListingCapabilityDecision(String listingId, List<CapabilityDecision> decisions) { }

    public record EvaluateBatchResponse(Instant evaluatedAt, List<ListingCapabilityDecision> listings) { }

    public record TimelineResponse(List<TimelineEntry> entries) { }
}
