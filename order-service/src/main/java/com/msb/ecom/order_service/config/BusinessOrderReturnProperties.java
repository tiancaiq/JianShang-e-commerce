package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public record BusinessOrderReturnProperties(
        boolean enabled,
        boolean processingEnabled,
        Duration window,
        Duration retryDelay,
        int batchSize) {

    public BusinessOrderReturnProperties(
            @Value("${business-orders.returns-enabled:false}") boolean enabled,
            @Value("${business-orders.return-processing-enabled:false}") boolean processingEnabled,
            @Value("${business-orders.return-window:P30D}") Duration window,
            @Value("${business-orders.return-retry-delay:PT2S}") Duration retryDelay,
            @Value("${business-orders.return-batch-size:20}") int batchSize) {
        this.enabled = enabled;
        this.processingEnabled = processingEnabled;
        this.window = window;
        this.retryDelay = retryDelay;
        this.batchSize = batchSize;
        if (!Duration.ofDays(30).equals(window)) {
            throw new IllegalArgumentException("LOCAL_DEMO_RETURN_POLICY_V1 requires a 30-day window.");
        }
        if (retryDelay.isNegative() || retryDelay.isZero() || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Return worker settings are invalid.");
        }
    }
}
