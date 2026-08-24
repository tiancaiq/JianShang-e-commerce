package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ListingAppealResolutionContracts {
    private ListingAppealResolutionContracts() { }

    public enum Outcome { UPHELD, REVOKED, MODIFIED }

    public enum EffectiveState { CLEAR, RESTRICTED, SUSPENDED }

    public record Replacement(ActionType actionType, Set<Scope> scopes,
            Instant effectiveAt, Instant expiresAt, String reasonCode, String reason) { }

    public record Executor(String actorId, String displayName, Set<String> permissions) { }

    public record Request(Outcome outcome, String enforcementActionId,
            Long expectedEnforcementVersion, Long expectedTargetVersion,
            String reasonCode, String reason, Replacement replacement,
            String idempotencyKey, Executor executor, Map<String, String> safeMetadata,
            Boolean confirmed, String confirmationToken, Boolean recoveryOnly) { }

    public record Response(String appealId, Outcome outcome,
            String originalEnforcementActionId, long originalEnforcementVersion,
            String replacementEnforcementActionId, Long replacementEnforcementVersion,
            long listingVersion, List<EffectiveRestriction> effectiveRestrictions,
            EffectiveState currentEffectiveEnforcementState, List<String> impactSummary,
            List<String> warnings, String confirmationToken, String correlationId,
            boolean dryRun, boolean replayed) { }
}
