package com.msb.ecom.product_service.search;

import java.time.Instant;

public record ListingSearchRebuildRun(
        String runId,
        String candidateGeneration,
        String previousReadGeneration,
        String previousWriteGeneration,
        long startWorkSequence,
        Long receiptWatermarkSequence,
        String state,
        int authoritativeDocumentCount,
        int vectorDocumentCount,
        int catchUpWorkCount,
        int deferredReceiptCount,
        String lastErrorCode,
        Instant startedAt,
        Instant updatedAt,
        Instant promotedAt
) {

    boolean dualWriteActive() {
        return switch (state) {
            case "DUAL_WRITE", "BACKFILLING", "CATCHING_UP", "PROMOTION_FENCED" -> true;
            default -> false;
        };
    }
}
