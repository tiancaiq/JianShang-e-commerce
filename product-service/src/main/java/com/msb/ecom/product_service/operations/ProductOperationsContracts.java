package com.msb.ecom.product_service.operations;

import java.time.Instant;
import java.util.List;

public final class ProductOperationsContracts {
    private ProductOperationsContracts() { }

    public record Job(String jobId, String jobType, String ownerService, String status,
            int attemptCount, Integer maxAttempts, Instant createdAt, Instant lastAttemptAt,
            Instant nextAttemptAt, String correlationId, String safeFailureCode,
            String safeFailureSummary, boolean retryable, String relatedTargetType,
            String relatedTargetId) { }
    public record Outbox(String eventId, String ownerService, String eventType,
            String aggregateType, String aggregateId, String status, int attemptCount,
            Instant createdAt, Instant lastAttemptAt, Instant nextAttemptAt,
            String correlationId, String safeFailureCode, String safeFailureSummary,
            boolean retryable) { }
    public record SearchIssue(String workId, String targetType, String targetId,
            String operationType, String status, int attemptCount, Instant lastAttemptAt,
            String safeFailureSummary, boolean retryable) { }
    public record SearchStatus(String ownerService, String status, long pendingOperations,
            long failedOperations, Instant lastSuccessfulIndexingAt, String indexVersion,
            String safeSummary, List<SearchIssue> issues) { }
    public record Feature(String featureKey, String displayName, String status,
            String scope, String source, Instant lastChangedAt, boolean mutable,
            String safeSummary) { }
    public record Snapshot(String ownerService, boolean available, String safeSummary,
            List<Job> jobs, List<Outbox> outbox, List<Object> reconciliation,
            List<Object> inventory, SearchStatus search, List<Feature> features) { }
    public record ActionRequest(String targetType, String targetId, String commandType,
            boolean dryRun, String reason, String correlationId) { }
    public record Action(String ownerService, String targetType, String targetId,
            String commandType, String currentState, boolean allowed, String denialReason,
            List<String> knownDependencies, List<String> warnings,
            String expectedOperation, String customerImpact, String result,
            String safeSummary) { }
}
