package com.msb.ecom.order_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class OrderCancellationMetrics {

    private final MeterRegistry registry;

    public OrderCancellationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void command(String result) {
        registry.counter(
                        "buyer.order.cancellation.requests",
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }
}
