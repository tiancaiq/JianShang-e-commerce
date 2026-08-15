package com.msb.ecom.product_service.search.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "listing.search.embedding")
public record ListingDiscoveryEmbeddingProperties(
        boolean requestEnabled,
        boolean sourceEnabled,
        boolean resultEnabled,
        String topic
) {

    private static final Pattern SAFE_TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    public ListingDiscoveryEmbeddingProperties {
        if (topic == null || !SAFE_TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException("Listing discovery embedding event topic is invalid.");
        }
    }
}
