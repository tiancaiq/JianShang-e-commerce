package com.msb.ecom.product_service.agentmedia;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
public class AgentListingMediaMetrics {

    private static final Set<String> RESULTS = Set.of(
            "success",
            "disabled",
            "unauthorized",
            "not_found",
            "rejected",
            "unavailable");

    private final MeterRegistry registry;

    public AgentListingMediaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String result, Duration duration) {
        if (!RESULTS.contains(result)) {
            throw new IllegalArgumentException("Unsupported listing-media metric result.");
        }
        registry.counter(
                "product.agent.listing_media.read.total",
                "result", result).increment();
        registry.timer(
                "product.agent.listing_media.read.duration",
                "result", result).record(duration);
    }
}
