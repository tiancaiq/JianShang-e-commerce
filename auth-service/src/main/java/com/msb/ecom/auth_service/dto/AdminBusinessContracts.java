package com.msb.ecom.auth_service.dto;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AdminBusinessContracts {

    private AdminBusinessContracts() {
    }

    public record SearchPage(
            List<Summary> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            String sort) {
    }

    public record Summary(
            String businessId,
            String displayName,
            String businessState,
            Instant createdAt,
            Instant updatedAt,
            long version,
            String ownerUserId,
            long memberCount,
            long activeListingCount,
            ActionType strongestActiveAction,
            Set<Scope> activeScopes,
            long activeEnforcementCount) {
    }

    public record Detail(
            String businessId,
            String displayName,
            String legalName,
            String businessState,
            String storeState,
            String verificationState,
            String verificationReference,
            Instant createdAt,
            Instant updatedAt,
            long version,
            OwnerSummary ownerSummary,
            List<MembershipSummary> membershipSummaries,
            ListingSummary listingSummary,
            OrderSummary orderSummary,
            ActionType strongestActiveAction,
            List<EffectiveRestriction> effectiveRestrictions,
            List<Result> activeEnforcementActions,
            List<Result> historicalEnforcementActions,
            AvailableAdminCapabilities availableAdminCapabilities) {
    }

    public record OwnerSummary(
            String userId,
            String safeDisplayName,
            String membershipState,
            ActionType strongestUserEnforcement) {
    }

    public record MembershipSummary(
            String userId,
            String safeDisplayName,
            String role,
            String membershipState,
            Instant joinedAt,
            ActionType strongestUserEnforcement) {
    }

    public record ListingSummary(
            long totalCount,
            long draftCount,
            long pendingReviewCount,
            long activeCount,
            long pausedCount,
            long removedCount) {
    }

    public record OrderSummary(
            boolean available,
            Long openOrderCount,
            Long historicalOrderCount,
            String note) {
    }

    public record AvailableAdminCapabilities(
            boolean canReadBusiness,
            boolean canRestrict,
            boolean canSuspend,
            boolean canBan,
            boolean canReinstate,
            boolean protectedBusiness,
            String readOnlyReason,
            Set<Scope> operationalScopes) {
    }

    public record CreateEnforcementRequest(
            ActionType actionType,
            Set<Scope> scopes,
            String reasonCode,
            String reason,
            Instant effectiveAt,
            Instant expiresAt,
            Long expectedBusinessVersion,
            String idempotencyKey,
            Map<String, String> safeMetadata) {
    }

    public record RevokeEnforcementRequest(
            Long expectedEnforcementVersion,
            String reasonCode,
            String reason,
            String idempotencyKey,
            Map<String, String> safeMetadata) {
    }

    public record EnforcementPreview(
            Result proposedAction,
            List<Result> overlappingActions,
            List<EffectiveRestriction> effectiveRestrictionsAfter,
            List<String> warnings,
            boolean permanent,
            boolean targetVersionCurrent,
            List<String> impactSummary) {
    }

    public record TimelineEntry(
            String eventId,
            Instant occurredAt,
            String eventType,
            String actorType,
            String actorId,
            String actorDisplayName,
            String source,
            String targetType,
            String targetId,
            String enforcementActionId,
            ActionType actionType,
            Set<Scope> scopes,
            String previousState,
            String newState,
            String reasonCode,
            String reason,
            String caseId,
            String correlationId,
            String requestId,
            Map<String, String> safeMetadata) {
    }

    public record EvaluateCapabilitiesRequest(String businessId, Set<Scope> scopes) {
    }

    public record EvaluateCapabilitiesBatchRequest(Set<String> businessIds, Set<Scope> scopes) {
    }

    public record EvaluateCapabilitiesResponse(
            String businessId,
            Instant evaluatedAt,
            List<CapabilityDecision> decisions) {
    }

    public record EvaluateCapabilitiesBatchResponse(
            Instant evaluatedAt,
            List<EvaluateCapabilitiesResponse> businesses) {
    }

    public record CapabilityDecision(
            Scope scope,
            boolean allowed,
            ActionType effectiveAction,
            String enforcementActionId,
            Instant effectiveAt,
            Instant expiresAt,
            String supportReference) {
    }

    public record BusinessMarketplaceCapabilities(
            String businessId,
            boolean listingCreationAllowed,
            boolean listingPublicationAllowed,
            boolean newSalesAllowed,
            List<CapabilityDecision> restrictions) {
    }
}
