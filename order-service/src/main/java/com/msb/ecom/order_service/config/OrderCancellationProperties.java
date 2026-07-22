package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OrderCancellationProperties {

    private final boolean enabled;
    private final Duration idempotencyRetention;
    private final int purgeBatchSize;

    public OrderCancellationProperties(
            @Value("${order.cancellation-requests.enabled:false}") boolean enabled,
            @Value("${order.cancellation-requests.idempotency-retention:P7D}")
                    Duration idempotencyRetention,
            @Value("${order.cancellation-requests.purge-batch-size:100}") int purgeBatchSize) {
        if (!Duration.ofDays(7).equals(idempotencyRetention)) {
            throw new IllegalArgumentException(
                    "Order cancellation idempotency retention must be P7D.");
        }
        if (purgeBatchSize < 1 || purgeBatchSize > 100) {
            throw new IllegalArgumentException(
                    "Order cancellation purge batch size must be from 1 through 100.");
        }
        this.enabled = enabled;
        this.idempotencyRetention = idempotencyRetention;
        this.purgeBatchSize = purgeBatchSize;
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration idempotencyRetention() {
        return idempotencyRetention;
    }

    public int purgeBatchSize() {
        return purgeBatchSize;
    }
}
