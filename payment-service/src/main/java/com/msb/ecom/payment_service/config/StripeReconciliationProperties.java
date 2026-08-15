package com.msb.ecom.payment_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public record StripeReconciliationProperties(
        boolean enabled,
        Duration minimumAge,
        int batchSize) {

    public StripeReconciliationProperties(
            @Value("${payment.stripe.reconciliation-enabled:false}") boolean enabled,
            @Value("${payment.stripe.reconciliation-min-age:PT15S}") Duration minimumAge,
            @Value("${payment.stripe.reconciliation-batch-size:25}") int batchSize) {
        this.enabled = enabled;
        this.minimumAge = minimumAge;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }
}
