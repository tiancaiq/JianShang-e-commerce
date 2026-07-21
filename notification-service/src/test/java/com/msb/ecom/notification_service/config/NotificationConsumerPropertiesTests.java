package com.msb.ecom.notification_service.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationConsumerPropertiesTests {

    @Test
    void acceptsOnlyApprovedLocalRetentionWithPurgeDisabled() {
        new NotificationConsumerProperties(
                false,
                "notification-service-order-confirmed-v2",
                Duration.ofDays(180),
                false);

        assertThatThrownBy(() -> new NotificationConsumerProperties(
                false,
                "notification-service-order-confirmed-v2",
                Duration.ofDays(30),
                false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationConsumerProperties(
                false,
                "notification-service-order-confirmed-v2",
                Duration.ofDays(180),
                true)).isInstanceOf(IllegalArgumentException.class);
    }
}
