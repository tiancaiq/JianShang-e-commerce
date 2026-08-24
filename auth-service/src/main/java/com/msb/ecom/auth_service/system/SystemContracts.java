package com.msb.ecom.auth_service.system;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SystemContracts {
    private SystemContracts() { }

    public record ServiceHealth(String serviceKey, String displayName, String status,
            Instant checkedAt, Long responseLatencyMs, String safeSummary) { }

    public record JobSummary(String jobId, String jobType, String ownerService, String status,
            int attemptCount, Integer maxAttempts, Instant createdAt, Instant lastAttemptAt,
            Instant nextAttemptAt, String correlationId, String safeFailureCode,
            String safeFailureSummary, boolean retryable, String relatedTargetType,
            String relatedTargetId) { }

    public record OutboxSummary(String eventId, String ownerService, String eventType,
            String aggregateType, String aggregateId, String status, int attemptCount,
            Instant createdAt, Instant lastAttemptAt, Instant nextAttemptAt,
            String correlationId, String safeFailureCode, String safeFailureSummary,
            boolean retryable) { }

    public record ReconciliationIssue(String issueId, String issueType, String ownerService,
            String targetType, String targetId, String localState, String externalStateSafe,
            String severity, String status, Instant lastCheckedAt, int attemptCount,
            String safeSummary, boolean retryable, String correlationId) { }

    public record InventoryIssue(String reservationId, String orderId, String listingId,
            int quantity, String status, Instant createdAt, Instant expiresAt, long ageSeconds,
            String issueType, String safeSummary, boolean retryable) { }

    public record SearchIssue(String workId, String targetType, String targetId,
            String operationType, String status, int attemptCount, Instant lastAttemptAt,
            String safeFailureSummary, boolean retryable) { }

    public record SearchStatus(String ownerService, String status, long pendingOperations,
            long failedOperations, Instant lastSuccessfulIndexingAt, String indexVersion,
            String safeSummary, List<SearchIssue> issues) { }

    public record FeatureState(String featureKey, String displayName, String status,
            String scope, String source, Instant lastChangedAt, boolean mutable,
            String safeSummary) { }

    public record SourceSnapshot(String ownerService, boolean available, String safeSummary,
            List<JobSummary> jobs, List<OutboxSummary> outbox,
            List<ReconciliationIssue> reconciliation, List<InventoryIssue> inventory,
            SearchStatus search, List<FeatureState> features) {
        public static SourceSnapshot unavailable(String owner, String summary) {
            return new SourceSnapshot(owner, false, summary, List.of(), List.of(), List.of(),
                    List.of(), null, List.of());
        }
    }

    public record CountPair(long primary, long secondary) { }

    public record SystemSummary(Instant generatedAt, CountPair serviceHealth, CountPair jobs,
            CountPair outbox, long reconciliationRequiresAttention, long inventoryPotentiallyStuck,
            long failedIndexOperations, long featureWarnings, List<String> sourceWarnings,
            List<OperationEvent> recentOperations) { }

    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) { }

    public record MaintenanceRequest(String reason, Long expectedVersion, String idempotencyKey) { }

    public record MaintenancePreview(String targetType, String targetId, String ownerService,
            String commandType, String currentState, boolean allowed, String denialReason,
            List<String> knownDependencies, List<String> warnings,
            String expectedOperation, String customerImpact) { }

    public record MaintenanceResult(String commandId, String targetType, String targetId,
            String ownerService, String commandType, String result, boolean replay,
            Instant requestedAt, String safeSummary) { }

    public record OwnerAction(String ownerService, String targetType, String targetId,
            String commandType, String currentState, boolean allowed, String denialReason,
            List<String> knownDependencies, List<String> warnings,
            String expectedOperation, String customerImpact, String result,
            String safeSummary) { }

    public record OperationEvent(String commandId, String eventType, String actorUserId,
            String targetType, String targetId, String reason, String outcome,
            String correlationId, Instant createdAt, Map<String, String> safeMetadata) { }
}
