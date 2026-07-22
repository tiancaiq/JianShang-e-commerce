package com.msb.ecom.notification_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void consumed(String result) {
        registry.counter(
                        "notifications.order_confirmed.consume",
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }
}
