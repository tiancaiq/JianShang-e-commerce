package com.msb.ecom.product_service.dto;

import java.util.List;

public record PublicListingSearchPageResponse(
        List<PublicListingResponse> data,
        PageMetadata page) {

    public record PageMetadata(
            String nextCursor,
            boolean hasMore) {
    }
}
