package com.msb.ecom.product_service.repository;

public record BusinessStoreItemStatusCounts(
        long total,
        long draft,
        long active,
        long paused,
        long removed) {
}
