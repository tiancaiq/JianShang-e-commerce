package com.msb.ecom.product_service.search.embedding;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ListingDiscoveryEmbeddingMetrics {

    private final MeterRegistry registry;

    public ListingDiscoveryEmbeddingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // Records only fixed operation/outcome labels; request and listing identifiers never become tags.
    public void record(String operation, String outcome) {
        registry.counter(
                "product.listing.discovery.embedding.operations",
                "operation", operation,
                "outcome", outcome).increment();
    }
}
