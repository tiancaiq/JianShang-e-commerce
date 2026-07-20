package com.msb.ecom.product_service.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CategoryGuidanceMetrics {

    private final MeterRegistry meterRegistry;
    private final String topic;

    public CategoryGuidanceMetrics(
            MeterRegistry meterRegistry,
            @Value("${category.guidance.publication.topic:category-guidance-v1}") String topic) {
        this.meterRegistry = meterRegistry;
        this.topic = topic;
    }

    public void command(String operation, String result) {
        meterRegistry.counter(
                "category_guidance_commands_total",
                "operation", operation,
                "result", result).increment();
    }

    public void sourceRead(String operation, String result) {
        meterRegistry.counter(
                "category_guidance_source_reads_total",
                "operation", operation,
                "result", result).increment();
    }

    public void categoryDeactivation(String result) {
        meterRegistry.counter(
                "category_guidance_category_deactivation_total",
                "result", result).increment();
    }

    public void outboxResult(String eventTopic, String result) {
        if (topic.equals(eventTopic)) {
            meterRegistry.counter(
                    "category_guidance_outbox_publication_total",
                    "result", result).increment();
        }
    }
}
