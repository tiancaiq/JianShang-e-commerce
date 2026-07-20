package com.msb.ecom.order_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class CheckoutMetrics {

    private final MeterRegistry registry;

    public CheckoutMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void command(String operation, String result) {
        registry.counter(
                        "checkout.commands",
                        "operation", operation.toLowerCase(Locale.ROOT),
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }

    public void reservation(String result) {
        registry.counter(
                        "checkout.reservation",
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }

    public void expired(int count) {
        if (count > 0) {
            registry.counter("checkout.expired").increment(count);
        }
    }

    public void invariant() {
        registry.counter("checkout.invariant.failures").increment();
    }
}
