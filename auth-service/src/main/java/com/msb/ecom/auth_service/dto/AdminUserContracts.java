package com.msb.ecom.auth_service.dto;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AdminUserContracts {

    private AdminUserContracts() {
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
            String userId,
            String safeDisplayName,
            String safeEmail,
            boolean emailMasked,
            Instant createdAt,
            Instant updatedAt,
            long version,
            String individualSellerStatus,
            long businessMembershipCount,
            boolean platformAdmin,
            ActionType strongestActiveAction,
            Set<Scope> activeScopes,
            long activeEnforcementCount) {
    }

    public record Detail(
            String userId,
            String safeDisplayName,
            String safeEmail,
            boolean emailMasked,
            Instant createdAt,
            Instant updatedAt,
            long version,
            String accountReference,
            String authenticationState,
            boolean emailVerified,
            String accountType,
            SellerSummary individualSellerProfile,
            List<BusinessMembershipSummary> businessMemberships,
            boolean platformAdmin,
            ActionType strongestActiveAction,
            List<EffectiveRestriction> effectiveRestrictions,
            List<Result> activeEnforcementActions,
            List<Result> historicalEnforcementActions,
            AvailableAdminCapabilities availableAdminCapabilities) {
    }

    public record SellerSummary(String status, Instant createdAt, Instant updatedAt, long version) {
    }

    public record BusinessMembershipSummary(
            String businessId,
            String businessDisplayName,
            String businessStatus,
            String membershipRole,
            String membershipStatus) {
    }

    public record AvailableAdminCapabilities(
            boolean canReadUser,
            boolean canViewPii,
            boolean canRestrict,
            boolean canSuspend,
            boolean canBan,
            boolean canReinstate,
            boolean self,
            boolean protectedAccount,
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
            Long expectedUserVersion,
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
            boolean targetVersionCurrent) {
    }

    public record EvaluateCapabilitiesRequest(String userId, Set<Scope> scopes) {
    }

    public record EvaluateCapabilitiesResponse(
            String userId,
            Instant evaluatedAt,
            List<CapabilityDecision> decisions) {
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

    public record UserMarketplaceCapabilities(
            boolean buyingAllowed,
            boolean sellingAllowed,
            boolean messagingAllowed,
            boolean marketplaceAccessAllowed,
            List<CapabilityDecision> restrictions) {
    }
}
