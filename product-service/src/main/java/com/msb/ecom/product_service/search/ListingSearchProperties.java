package com.msb.ecom.product_service.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "listing.search")
public record ListingSearchProperties(
        String engine,
        OpenSearchProperties opensearch
) {

    public boolean openSearchEnabled() {
        return "opensearch".equalsIgnoreCase(engine);
    }

    public record OpenSearchProperties(
            String baseUrl,
            String index,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean initializeIndex
    ) {
    }
}
