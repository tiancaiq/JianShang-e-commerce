package com.msb.ecom.notification_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationReadMetrics {

    private final MeterRegistry registry;

    public NotificationReadMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void outcome(String operation, String result) {
        registry.counter(
                        "notifications.read_api",
                        "operation", operation,
                        "result", result)
                .increment();
    }
}
