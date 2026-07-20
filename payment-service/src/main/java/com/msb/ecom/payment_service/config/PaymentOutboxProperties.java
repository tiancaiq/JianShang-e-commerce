package com.msb.ecom.payment_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class PaymentOutboxProperties {

    private final boolean enabled;
    private final boolean workerEnabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimLease;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public PaymentOutboxProperties(
            @Value("${payment.outbox.dispatch.enabled:false}") boolean enabled,
            @Value("${payment.outbox.dispatch.worker-enabled:false}") boolean workerEnabled,
            @Value("${payment.outbox.dispatch.batch-size:25}") int batchSize,
            @Value("${payment.outbox.dispatch.max-attempts:10}") int maxAttempts,
            @Value("${payment.outbox.dispatch.claim-lease:PT30S}") Duration claimLease,
            @Value("${payment.outbox.dispatch.initial-backoff:PT5S}") Duration initialBackoff,
            @Value("${payment.outbox.dispatch.max-backoff:PT5M}") Duration maxBackoff) {
        this.enabled = enabled;
        this.workerEnabled = workerEnabled;
        this.batchSize = bounded(batchSize, 1, 100, "Payment outbox batch size");
        this.maxAttempts = bounded(maxAttempts, 1, 100, "Payment outbox maximum attempts");
        this.claimLease = boundedDuration(claimLease, Duration.ofSeconds(1), Duration.ofMinutes(5),
                "Payment outbox claim lease");
        this.initialBackoff = boundedDuration(initialBackoff, Duration.ofMillis(100), Duration.ofMinutes(5),
                "Payment outbox initial backoff");
        this.maxBackoff = boundedDuration(maxBackoff, this.initialBackoff, Duration.ofHours(1),
                "Payment outbox maximum backoff");
        if (workerEnabled && !enabled) {
            throw new IllegalStateException(
                    "Payment outbox worker cannot be enabled while dispatch is disabled.");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean workerEnabled() {
        return workerEnabled;
    }

    public int batchSize() {
        return batchSize;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration claimLease() {
        return claimLease;
    }

    // Calculates deterministic capped backoff from the number of prior failures.
    public Duration backoffAfter(int priorFailures) {
        Duration result = initialBackoff;
        for (int index = 0; index < priorFailures && result.compareTo(maxBackoff) < 0; index++) {
            if (result.compareTo(maxBackoff.dividedBy(2)) > 0) {
                return maxBackoff;
            }
            result = result.multipliedBy(2);
        }
        return result.compareTo(maxBackoff) > 0 ? maxBackoff : result;
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be from " + minimum + " through " + maximum + ".");
        }
        return value;
    }

    private static Duration boundedDuration(
            Duration value,
            Duration minimum,
            Duration maximum,
            String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must be from " + minimum + " through " + maximum + ".");
        }
        return value;
    }
}
