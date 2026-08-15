package com.msb.ecom.auth_service.enforcement;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EnforcementContracts {

    private EnforcementContracts() {
    }

    public enum TargetType { USER, BUSINESS, LISTING }

    public enum ActionType {
        RESTRICT(1), SUSPEND(2), BAN(3);

        private final int severity;

        ActionType(int severity) {
            this.severity = severity;
        }

        public int severity() {
            return severity;
        }
    }

    public enum Scope {
        USER_LOGIN(TargetType.USER),
        USER_BUYING(TargetType.USER),
        USER_SELLING(TargetType.USER),
        USER_MESSAGING(TargetType.USER),
        BUSINESS_LISTING_CREATION(TargetType.BUSINESS),
        BUSINESS_LISTING_PUBLICATION(TargetType.BUSINESS),
        BUSINESS_NEW_SALES(TargetType.BUSINESS),
        BUSINESS_PAYOUTS(TargetType.BUSINESS),
        LISTING_PUBLIC_VISIBILITY(TargetType.LISTING),
        LISTING_PURCHASABILITY(TargetType.LISTING);

        private final TargetType targetType;

        Scope(TargetType targetType) {
            this.targetType = targetType;
        }

        public TargetType targetType() {
            return targetType;
        }
    }

    public enum Source { HUMAN_ADMIN, SYSTEM, AI_AGENT }

    public enum LifecycleState { ACTIVE, EXPIRED, REVOKED }

    public record CreateCommand(
            TargetType targetType,
            String targetId,
            ActionType actionType,
            Set<Scope> scopes,
            String reasonCode,
            String reason,
            String caseId,
            Instant effectiveAt,
            Instant expiresAt,
            Long expectedTargetVersion,
            String idempotencyKey,
            Map<String, String> safeMetadata,
            boolean dryRun
    ) {
    }

    public record RevokeCommand(
            String enforcementActionId,
            Long expectedEnforcementVersion,
            String reasonCode,
            String reason,
            String idempotencyKey,
            Map<String, String> safeMetadata,
            boolean dryRun
    ) {
    }

    public record EffectiveRestriction(Scope scope, ActionType actionType, String enforcementActionId) {
    }

    public record Result(
            String enforcementActionId,
            TargetType targetType,
            String targetId,
            ActionType actionType,
            Set<Scope> scopes,
            LifecycleState lifecycleState,
            Instant effectiveAt,
            Instant expiresAt,
            long version,
            Instant createdAt,
            Instant revokedAt,
            String reasonCode,
            String reason,
            List<EffectiveRestriction> effectiveRestrictions,
            String correlationId,
            boolean dryRun
    ) {
    }

    public record TimelineEntry(
            String eventId,
            Instant occurredAt,
            String eventType,
            String actorType,
            String actorId,
            String actorDisplayName,
            Source source,
            TargetType targetType,
            String targetId,
            String enforcementActionId,
            String caseId,
            String previousState,
            String newState,
            ActionType actionType,
            Set<Scope> scopes,
            String reasonCode,
            String reason,
            String correlationId,
            String requestId,
            Map<String, String> safeMetadata
    ) {
    }
}
