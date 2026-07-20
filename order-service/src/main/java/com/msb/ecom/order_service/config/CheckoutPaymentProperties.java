package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
public class CheckoutPaymentProperties {

    private final boolean enabled;
    private final String serviceUrl;
    private final String internalServiceToken;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public CheckoutPaymentProperties(
            @Value("${checkout.payment-integration.enabled:false}") boolean enabled,
            @Value("${service.payment.url:http://localhost:8084}") String serviceUrl,
            @Value("${service.payment.internal-service-token:}") String internalServiceToken,
            @Value("${checkout.payment-integration.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${checkout.payment-integration.read-timeout:PT3S}") Duration readTimeout) {
        this.enabled = enabled;
        this.serviceUrl = requiredUrl(serviceUrl);
        this.internalServiceToken = internalServiceToken == null ? "" : internalServiceToken;
        this.connectTimeout = boundedTimeout(connectTimeout, "Payment connect timeout");
        this.readTimeout = boundedTimeout(readTimeout, "Payment read timeout");
        if (enabled && this.internalServiceToken.isBlank()) {
            throw new IllegalStateException(
                    "Payment integration requires an internal service token when enabled.");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String serviceUrl() {
        return serviceUrl;
    }

    public String internalServiceToken() {
        return internalServiceToken;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    private static String requiredUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Payment service URL is required.");
        }
        URI uri = URI.create(value.trim());
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Payment service URL must use HTTP or HTTPS.");
        }
        return uri.toString();
    }

    private static Duration boundedTimeout(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(name + " must be greater than zero and at most 30 seconds.");
        }
        return value;
    }
}
