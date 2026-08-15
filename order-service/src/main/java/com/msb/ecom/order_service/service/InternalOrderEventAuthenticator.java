package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.OrderConfirmationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalOrderEventAuthenticator {

    private final boolean configured;
    private final byte[] expectedDigest;

    public InternalOrderEventAuthenticator(
            @Value("${commerce.internal-service-token:}") String token) {
        this.configured = !token.isBlank();
        this.expectedDigest = digest(token);
    }

    public void requireAuthenticated(String supplied) {
        if (!configured || !MessageDigest.isEqual(expectedDigest, digest(supplied == null ? "" : supplied))) {
            throw new OrderConfirmationException(
                    "ORDER_INTERNAL_AUTH_REQUIRED",
                    "Internal order event authentication is required.");
        }
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
