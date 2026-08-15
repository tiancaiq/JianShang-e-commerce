package com.msb.ecom.product_service.search.operator;

import com.msb.ecom.product_service.search.ListingSearchRebuildRun;
import com.msb.ecom.product_service.search.ListingSearchPromotionEligibility;

import java.time.Instant;
import java.util.Set;

public record ListingSearchOperatorResponse(
        String schemaVersion,
        String runId,
        String commandOutcome,
        String state,
        String schemaIdentity,
        String candidateRole,
        String previousGenerationRole,
        int authoritativeDocumentCount,
        int vectorDocumentCount,
        int catchUpWorkCount,
        int deferredReceiptCount,
        boolean canCatchUp,
        boolean canPromote,
        boolean canRecover,
        String errorCode,
        Instant startedAt,
        Instant updatedAt,
        Instant promotedAt
) {
    public static final String SCHEMA = "MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2";
    private static final Set<String> SAFE_ERRORS = Set.of(
            "PREPARATION_FAILED",
            "PROMOTION_FAILED",
            "ALIAS_OUTCOME_UNKNOWN");

    // Converts durable state to a bounded operator view without exposing physical index names.
    public static ListingSearchOperatorResponse from(
            ListingSearchRebuildRun run,
            String commandOutcome,
            ListingSearchPromotionEligibility eligibility) {
        boolean promoted = "PROMOTED".equals(run.state());
        String error = run.lastErrorCode() == null
                ? null
                : SAFE_ERRORS.contains(run.lastErrorCode())
                        ? run.lastErrorCode()
                        : "INTERNAL_FAILURE";
        return new ListingSearchOperatorResponse(
                SCHEMA,
                run.runId(),
                commandOutcome,
                run.state(),
                "marketplace-public-listing-v2-vector",
                promoted ? "ACTIVE_V2" : "INACTIVE_V2_CANDIDATE",
                promoted ? "RETAINED_PREVIOUS" : "ACTIVE_PREVIOUS",
                run.authoritativeDocumentCount(),
                run.vectorDocumentCount(),
                run.catchUpWorkCount(),
                run.deferredReceiptCount(),
                eligibility.canCatchUp(),
                eligibility.canPromote(),
                eligibility.canRecover(),
                error,
                run.startedAt(),
                run.updatedAt(),
                run.promotedAt());
    }
}
