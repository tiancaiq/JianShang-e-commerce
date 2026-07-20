package com.msb.ecom.product_service.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "listing.knowledge.publication")
public record ListingKnowledgePublicationProperties(
        boolean backfillEnabled,
        boolean publisherEnabled,
        String topic,
        int batchSize,
        long pollIntervalMs,
        long claimSeconds,
        Duration sendTimeout,
        Duration retryBase,
        Duration retryMax
) {

    private static final Pattern SAFE_TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    public ListingKnowledgePublicationProperties {
        if (topic == null || !SAFE_TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException("Listing knowledge event topic is invalid.");
        }
        if (batchSize < 1 || batchSize > 200) {
            throw new IllegalArgumentException("Listing knowledge publish batch size must be between 1 and 200.");
        }
        if (pollIntervalMs < 100) {
            throw new IllegalArgumentException("Listing knowledge publish interval must be at least 100 ms.");
        }
        if (claimSeconds < 5 || claimSeconds > 600) {
            throw new IllegalArgumentException("Listing knowledge claim duration must be between 5 and 600 seconds.");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("Listing knowledge send timeout must be positive.");
        }
        if (sendTimeout.toMillis() > (claimSeconds * 1000L) / batchSize) {
            throw new IllegalArgumentException(
                    "Listing knowledge claim duration must cover the configured batch send time.");
        }
        if (retryBase == null || retryBase.isNegative() || retryBase.isZero()) {
            throw new IllegalArgumentException("Listing knowledge retry base must be positive.");
        }
        if (retryMax == null || retryMax.compareTo(retryBase) < 0) {
            throw new IllegalArgumentException("Listing knowledge retry max must not be less than retry base.");
        }
    }
}
