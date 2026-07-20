package com.msb.ecom.auth_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalCommerceAuthenticator {

    private final byte[] internalServiceToken;

    public InternalCommerceAuthenticator(
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.internalServiceToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    // Authenticates internal commerce calls without leaking token comparison timing.
    public void require(String suppliedToken) {
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(internalServiceToken, actual)) {
            throw new InternalCommerceAuthorizationException();
        }
    }
}
