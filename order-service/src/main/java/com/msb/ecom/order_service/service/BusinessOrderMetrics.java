package com.msb.ecom.order_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class BusinessOrderMetrics {

    private final MeterRegistry registry;

    public BusinessOrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void read(String operation, String result) {
        registry.counter(
                        "business.orders.reads",
                        "operation", operation.toLowerCase(Locale.ROOT),
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }

    public void accept(String result) {
        registry.counter(
                        "business.orders.acceptance",
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }
}
