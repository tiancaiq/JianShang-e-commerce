package com.msb.ecom.inventory_service.service;

import com.msb.ecom.inventory_service.model.InventoryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalCommerceAuthenticator {

    private final byte[] expectedToken;

    public InternalCommerceAuthenticator(
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.expectedToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    // Applies constant-time authentication to inventory's internal commerce routes.
    public void requireAuthenticated(String suppliedToken) {
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, actual)) {
            throw new InventoryException(
                    HttpStatus.FORBIDDEN,
                    "INVENTORY_INTERNAL_AUTH_REQUIRED",
                    "Internal commerce service authentication is required.");
        }
    }
}
