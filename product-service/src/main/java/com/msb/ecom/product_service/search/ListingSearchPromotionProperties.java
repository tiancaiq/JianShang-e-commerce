package com.msb.ecom.product_service.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "listing.search.promotion")
public record ListingSearchPromotionProperties(
        boolean rebuildEnabled,
        boolean promotionEnabled,
        int lockWaitSeconds,
        int maxCatchUpWork
) {

    public int effectiveLockWaitSeconds() {
        return lockWaitSeconds < 1 ? 5 : Math.min(lockWaitSeconds, 30);
    }

    public int effectiveMaxCatchUpWork() {
        return maxCatchUpWork < 1 ? 10_000 : Math.min(maxCatchUpWork, 100_000);
    }
}
