package com.msb.ecom.auth_service.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class AddressBookMetrics {

    private final MeterRegistry registry;

    public AddressBookMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void command(String operation, String result) {
        registry.counter(
                        "identity.address.commands",
                        "operation", operation.toLowerCase(Locale.ROOT),
                        "result", result.toLowerCase(Locale.ROOT))
                .increment();
    }

    public void limitConflict() {
        registry.counter("identity.address.limit.conflicts").increment();
    }

    public void versionConflict() {
        registry.counter("identity.address.version.conflicts").increment();
    }

    public void internalResolver(String result, long elapsedNanos) {
        Timer.builder("identity.address.internal.resolver")
                .tag("result", result.toLowerCase(Locale.ROOT))
                .register(registry)
                .record(Duration.ofNanos(elapsedNanos));
    }
}
