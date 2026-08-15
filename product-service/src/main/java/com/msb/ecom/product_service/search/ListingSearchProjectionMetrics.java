package com.msb.ecom.product_service.search;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.springframework.stereotype.Component;

@Component
public class ListingSearchProjectionMetrics {

    private static final String METRIC_NAME = "product.listing.search.projection.operations";

    private final MeterRegistry meterRegistry;

    public ListingSearchProjectionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // Records only fixed operation and outcome labels so listing and query data never become metric tags.
    public void record(String operation, String outcome) {
        meterRegistry.counter(
                METRIC_NAME,
                "operation", operation,
                "outcome", outcome).increment();
    }

    // Exposes backlog size without listing, actor, request, or error data in labels.
    public void registerPendingGauge(ListingSearchProjectionWorkRepository repository) {
        Gauge.builder("product.listing.search.projection.pending", repository, value -> value.pendingCount())
                .register(meterRegistry);
    }
}
