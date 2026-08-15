package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public record OrderCancellationProcessingProperties(
        boolean enabled,
        int batchSize,
        Duration retryDelay
) {
    public OrderCancellationProcessingProperties(
            @Value("${order.cancellation-processing.enabled:false}") boolean enabled,
            @Value("${order.cancellation-processing.batch-size:25}") int batchSize,
            @Value("${order.cancellation-processing.retry-delay:PT2S}") Duration retryDelay) {
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.retryDelay = retryDelay.isNegative() || retryDelay.isZero()
                ? Duration.ofSeconds(2) : retryDelay;
    }
}
