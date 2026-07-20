package com.msb.ecom.product_service.dto;

import java.util.List;

public record BusinessStoreItemCommerceContextPageResponse(
        List<BusinessStoreItemCommerceContextResponse> data,
        PageMetadata page
) {
    public record PageMetadata(String nextCursor, boolean hasMore) {
    }
}
