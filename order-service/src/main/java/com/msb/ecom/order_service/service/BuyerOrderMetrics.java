package com.msb.ecom.order_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class BuyerOrderMetrics {

    private final MeterRegistry registry;

    public BuyerOrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void read(String operation, String result) {
        registry.counter(
                        "buyer.orders.reads",
                        "operation", operation.toLowerCase(Locale.ROOT),
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }
}
