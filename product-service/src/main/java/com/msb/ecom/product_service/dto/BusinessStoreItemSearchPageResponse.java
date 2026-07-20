package com.msb.ecom.product_service.dto;

import java.util.List;

public record BusinessStoreItemSearchPageResponse(
        List<ListingDraftResponse> data,
        PageMetadata page,
        StatusSummary summary) {

    public record PageMetadata(
            String nextCursor,
            boolean hasMore) {
    }

    public record StatusSummary(
            long total,
            long draft,
            long active,
            long paused,
            long removed) {
    }
}
