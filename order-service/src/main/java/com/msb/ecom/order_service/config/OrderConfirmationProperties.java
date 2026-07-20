package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OrderConfirmationProperties {

    private final boolean enabled;
    private final String consumerName;
    private final Duration processingLease;

    public OrderConfirmationProperties(
            @Value("${order.confirmation.enabled:false}") boolean enabled,
            @Value("${order.confirmation.consumer-name:order-service-order-confirmation-v1}")
            String consumerName,
            @Value("${order.confirmation.processing-lease:PT30S}") Duration processingLease) {
        this.enabled = enabled;
        this.consumerName = requiredConsumerName(consumerName);
        if (processingLease == null
                || processingLease.compareTo(Duration.ofSeconds(5)) < 0
                || processingLease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "Order confirmation processing lease must be from 5 seconds through 5 minutes.");
        }
        this.processingLease = processingLease;
    }

    public boolean enabled() {
        return enabled;
    }

    public String consumerName() {
        return consumerName;
    }

    public Duration processingLease() {
        return processingLease;
    }

    private static String requiredConsumerName(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]{2,99}")) {
            throw new IllegalArgumentException("Order confirmation consumer name is invalid.");
        }
        return value;
    }
}
