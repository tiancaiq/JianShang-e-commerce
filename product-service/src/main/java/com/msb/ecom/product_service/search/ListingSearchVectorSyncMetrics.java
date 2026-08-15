package com.msb.ecom.product_service.search;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ListingSearchVectorSyncMetrics {

    private static final String OPERATIONS =
            "product.listing.search.vector.sync.operations";

    private final MeterRegistry meterRegistry;

    public ListingSearchVectorSyncMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // Records fixed operation/outcome labels without receipt, listing, hash, text, or target data.
    public void record(String operation, String outcome) {
        meterRegistry.counter(
                OPERATIONS,
                "operation", operation,
                "outcome", outcome).increment();
    }

    public void registerPendingGauge(ListingSearchVectorApplyWorkRepository repository) {
        Gauge.builder(
                        "product.listing.search.vector.sync.pending",
                        repository,
                        value -> value.pendingCount())
                .register(meterRegistry);
    }
}
