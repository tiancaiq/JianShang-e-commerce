package com.msb.ecom.auth_service.service;

import java.util.Map;
import java.util.Set;

public interface ProductBusinessListingSummaryClient {

    Map<String, Summary> summaries(Set<String> businessIds);

    record Summary(
            String businessId,
            long totalCount,
            long draftCount,
            long pendingReviewCount,
            long activeCount,
            long pausedCount,
            long removedCount) {
    }
}
