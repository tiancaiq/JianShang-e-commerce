package com.msb.ecom.product_service.dto;

import java.util.List;
import java.util.Set;

public final class AdminBusinessListingSummaryContracts {
    private AdminBusinessListingSummaryContracts() { }

    public record Request(Set<String> businessIds) { }

    public record Response(
            String businessId,
            long totalCount,
            long draftCount,
            long pendingReviewCount,
            long activeCount,
            long pausedCount,
            long removedCount) { }

    public record Envelope(List<Response> items) { }
}
