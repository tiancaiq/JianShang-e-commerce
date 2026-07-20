package com.msb.ecom.product_service.knowledge;

import java.time.Instant;
import java.util.List;

public record CategoryGuidancePageResponse(
        List<CategoryGuidanceSourceResponse> items,
        String nextCursor,
        boolean hasMore,
        Instant exportWatermark
) {
}
