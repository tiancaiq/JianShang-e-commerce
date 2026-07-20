package com.msb.ecom.product_service.knowledge;

import java.time.Instant;
import java.util.List;

public record ListingKnowledgeExportResponse(
        List<ListingKnowledgeSourceResponse> items,
        String nextCursor,
        boolean hasMore,
        Instant exportWatermark
) {
}
