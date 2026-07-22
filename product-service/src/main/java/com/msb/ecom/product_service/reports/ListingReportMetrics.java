package com.msb.ecom.product_service.reports;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class ListingReportMetrics {

    private final MeterRegistry registry;

    void intake(String result, String reasonCode) {
        registry.counter(
                "product.listing.reports.intake",
                "result", result,
                "reason", reasonCode == null ? "none" : reasonCode).increment();
    }

    void read(String result) {
        registry.counter("product.listing.reports.read", "result", result).increment();
    }

    void rateLimit(String bucketType) {
        registry.counter("product.listing.reports.rate.limit", "bucket", bucketType).increment();
    }
}
