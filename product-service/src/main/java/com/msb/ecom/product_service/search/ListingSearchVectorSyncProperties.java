package com.msb.ecom.product_service.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "listing.search.vector-sync")
public record ListingSearchVectorSyncProperties(
        boolean enabled,
        boolean workerEnabled,
        int batchSize,
        long pollIntervalMs,
        long claimSeconds,
        int maxAttempts,
        Duration retryBase,
        Duration retryMax,
        int catchUpBatchSize
) {

    public int effectiveBatchSize() {
        return batchSize <= 0 ? 25 : Math.min(batchSize, 100);
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
        return configured.compareTo(effectiveRetryBase()) < 0
                ? effectiveRetryBase()
                : configured;
    }

    public int effectiveCatchUpBatchSize() {
        return catchUpBatchSize <= 0 ? 100 : Math.min(catchUpBatchSize, 500);
    }
}
