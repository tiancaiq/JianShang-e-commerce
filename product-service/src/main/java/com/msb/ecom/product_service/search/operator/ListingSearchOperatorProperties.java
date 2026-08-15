package com.msb.ecom.product_service.search.operator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "listing.search.operator")
public record ListingSearchOperatorProperties(
        boolean commandsEnabled,
        boolean statusEnabled
) {
}
