package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BusinessOrderAcceptanceProperties {

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int PURGE_BATCH_SIZE = 50;

    private final boolean enabled;

    public BusinessOrderAcceptanceProperties(
            @Value("${business-orders.acceptance-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration retention() {
        return RETENTION;
    }

    public int purgeBatchSize() {
        return PURGE_BATCH_SIZE;
    }
}
