package com.msb.ecom.inventory_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class InventoryReservationMetrics {

    private final MeterRegistry registry;

    public InventoryReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void command(String operation, String result) {
        registry.counter(
                        "inventory.reservation.commands",
                        "operation", operation.toLowerCase(Locale.ROOT),
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }

    public void expired(int count) {
        if (count > 0) {
            registry.counter("inventory.reservation.expired").increment(count);
        }
    }

    public void recoveryRequired() {
        registry.counter("inventory.reservation.recovery.required").increment();
    }
}
