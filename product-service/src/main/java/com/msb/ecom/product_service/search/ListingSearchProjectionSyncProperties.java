package com.msb.ecom.product_service.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "listing.search.projection-sync")
public record ListingSearchProjectionSyncProperties(
        boolean enabled,
        int batchSize,
        long pollIntervalMs,
        long claimSeconds,
        int maxAttempts,
        Duration retryBase,
        Duration retryMax
) {

    public int effectiveBatchSize() {
        return batchSize <= 0 ? 50 : Math.min(batchSize, 250);
    }

    public long effectiveClaimSeconds() {
        return claimSeconds <= 0 ? 60 : Math.min(claimSeconds, 600);
    }

    public int effectiveMaxAttempts() {
        return maxAttempts <= 0 ? 20 : Math.min(maxAttempts, 100);
    }

    public Duration effectiveRetryBase() {
        return retryBase == null || retryBase.isNegative() || retryBase.isZero()
                ? Duration.ofSeconds(2)
                : retryBase;
    }

    public Duration effectiveRetryMax() {
        Duration configured = retryMax == null || retryMax.isNegative() || retryMax.isZero()
                ? Duration.ofMinutes(5)
                : retryMax;
        return configured.compareTo(effectiveRetryBase()) < 0 ? effectiveRetryBase() : configured;
    }
}
