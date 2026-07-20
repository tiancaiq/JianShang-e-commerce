package com.msb.ecom.product_service.knowledge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ListingKnowledgePublicationMetrics {

    private final MeterRegistry registry;

    public ListingKnowledgePublicationMetrics(
            MeterRegistry registry,
            ListingKnowledgeRepository repository) {
        this.registry = registry;
        Gauge.builder("product.listing.knowledge.outbox.unpublished", repository,
                        value -> value.unpublishedCount())
                .register(registry);
        Gauge.builder("product.listing.knowledge.outbox.oldest.age.seconds", repository,
                        value -> value.oldestUnpublishedAgeSeconds())
                .register(registry);
    }

    public void published() {
        registry.counter("product.listing.knowledge.outbox.publish", "result", "success").increment();
    }

    public void failed(String errorCode) {
        registry.counter(
                        "product.listing.knowledge.outbox.publish",
                        "result", "failure",
                        "error", errorCode)
                .increment();
    }

    public void lostClaim() {
        registry.counter("product.listing.knowledge.outbox.publish", "result", "lost_claim").increment();
    }
}
