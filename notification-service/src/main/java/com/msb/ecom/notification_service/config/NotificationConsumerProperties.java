package com.msb.ecom.notification_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

@ConfigurationProperties("notifications.order-confirmed")
public record NotificationConsumerProperties(
        boolean enabled,
        String consumerName,
        Duration retention,
        boolean purgeEnabled
) {

    private static final Pattern CONSUMER_NAME =
            Pattern.compile("[A-Za-z0-9._:-]{8,100}");
    private static final Duration LOCAL_RETENTION = Duration.ofDays(180);

    public NotificationConsumerProperties {
        if (consumerName == null || !CONSUMER_NAME.matcher(consumerName).matches()) {
            throw new IllegalArgumentException("Notification consumer name is invalid.");
        }
        if (!LOCAL_RETENTION.equals(retention)) {
            throw new IllegalArgumentException(
                    "NOT-01A supports only local/test P180D retention metadata.");
        }
        if (purgeEnabled) {
            throw new IllegalArgumentException(
                    "Notification purge requires later legal and operations approval.");
        }
    }
}
