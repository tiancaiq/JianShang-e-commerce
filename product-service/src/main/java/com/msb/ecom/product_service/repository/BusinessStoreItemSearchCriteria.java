package com.msb.ecom.product_service.repository;

import java.time.Instant;

public record BusinessStoreItemSearchCriteria(
        String keyword,
        String status,
        Instant cursorUpdatedAt,
        String cursorListingId) {
}
