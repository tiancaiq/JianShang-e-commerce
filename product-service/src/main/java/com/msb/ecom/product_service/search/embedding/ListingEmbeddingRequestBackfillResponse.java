package com.msb.ecom.product_service.search.embedding;

import java.time.Instant;
import java.util.Set;

public record ListingEmbeddingRequestBackfillResponse(
        String schemaVersion,
        String runId,
        String commandOutcome,
        String state,
        int pageCount,
        int processedCount,
        int createdCount,
        int alreadyPresentCount,
        int skippedCount,
        int failedCount,
        String errorCode,
        Instant startedAt,
        Instant updatedAt,
        Instant completedAt
) {
    public static final String SCHEMA =
            "MARKETPLACE_LISTING_EMBEDDING_REQUEST_BACKFILL_STATUS_V1";
    private static final Set<String> SAFE_ERRORS = Set.of(
            "LISTING_EMBEDDING_BACKFILL_BOUND_EXCEEDED",
            "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE");

    // Exposes bounded progress without the listing watermark, cursor, request IDs, or hashes.
    public static ListingEmbeddingRequestBackfillResponse from(
            ListingEmbeddingRequestBackfillRun run,
            String commandOutcome) {
        String error = run.lastErrorCode() == null
                ? null
                : SAFE_ERRORS.contains(run.lastErrorCode())
                        ? run.lastErrorCode()
                        : "INTERNAL_FAILURE";
        return new ListingEmbeddingRequestBackfillResponse(
                SCHEMA,
                run.runId(),
                commandOutcome,
                run.state(),
                run.pageCount(),
                run.processedCount(),
                run.createdCount(),
                run.alreadyPresentCount(),
                run.skippedCount(),
                run.failedCount(),
                error,
                run.startedAt(),
                run.updatedAt(),
                run.completedAt());
    }
}
