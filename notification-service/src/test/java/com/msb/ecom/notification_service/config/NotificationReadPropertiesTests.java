package com.msb.ecom.notification_service.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationReadPropertiesTests {

    @Test
    void validatesLocalDefaultOffAuthBoundary() {
        var properties = new NotificationReadProperties(
                false,
                "http://localhost:8081/",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.authServiceUrl()).isEqualTo("http://localhost:8081");
        assertThatThrownBy(() -> new NotificationReadProperties(
                true,
                "file:///auth",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
