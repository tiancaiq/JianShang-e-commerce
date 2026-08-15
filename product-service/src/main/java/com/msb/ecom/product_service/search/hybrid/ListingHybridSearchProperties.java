package com.msb.ecom.product_service.search.hybrid;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "listing.search.hybrid")
public record ListingHybridSearchProperties(
        boolean enabled,
        boolean singleBranchFallbackEnabled
) {
}
