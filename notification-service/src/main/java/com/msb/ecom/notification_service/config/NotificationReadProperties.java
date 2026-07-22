package com.msb.ecom.notification_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("notifications.read-api")
public record NotificationReadProperties(
        boolean enabled,
        String authServiceUrl,
        Duration connectTimeout,
        Duration readTimeout
) {

    private static final Duration MIN_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(10);

    public NotificationReadProperties {
        authServiceUrl = validUrl(authServiceUrl);
        connectTimeout = bounded(connectTimeout, "Notification Auth connect timeout");
        readTimeout = bounded(readTimeout, "Notification Auth read timeout");
    }

    private static String validUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Notification Auth service URL is required.");
        }
        URI uri = URI.create(value.trim());
        if ((!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Notification Auth service URL is invalid.");
        }
        return value.trim().replaceAll("/+$", "");
    }

    private static Duration bounded(Duration value, String label) {
        if (value == null || value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException(label + " must be from PT0.1S through PT10S.");
        }
        return value;
    }
}
