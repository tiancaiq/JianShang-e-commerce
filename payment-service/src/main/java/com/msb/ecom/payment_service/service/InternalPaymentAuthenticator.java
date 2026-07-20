package com.msb.ecom.payment_service.service;

import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class InternalPaymentAuthenticator {

    private final boolean configured;
    private final byte[] expectedDigest;

    public InternalPaymentAuthenticator(
            @Value("${payment.internal-service-token:}") String internalServiceToken) {
        configured = !internalServiceToken.isEmpty();
        expectedDigest = digest(internalServiceToken);
    }

    // Keeps the internal contract closed when no service credential is configured.
    public void requireAuthenticated(String suppliedToken) {
        byte[] actualDigest = digest(suppliedToken == null ? "" : suppliedToken);
        if (!configured || !MessageDigest.isEqual(expectedDigest, actualDigest)) {
            throw new PaymentIntentException(
                    HttpStatus.FORBIDDEN,
                    "PAYMENT_INTERNAL_AUTH_REQUIRED",
                    "Internal payment service authentication is required.");
        }
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
