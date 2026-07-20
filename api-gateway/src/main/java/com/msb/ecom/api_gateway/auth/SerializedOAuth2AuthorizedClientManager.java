package com.msb.ecom.api_gateway.auth;

import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes authorization for the same OAuth client and principal so strict
 * refresh-token rotation cannot consume one refresh token concurrently.
 */
public final class SerializedOAuth2AuthorizedClientManager implements OAuth2AuthorizedClientManager {

    private static final int LOCK_STRIPES = 64;

    private final OAuth2AuthorizedClientManager delegate;
    private final ReentrantLock[] locks;

    public SerializedOAuth2AuthorizedClientManager(OAuth2AuthorizedClientManager delegate) {
        this.delegate = delegate;
        this.locks = new ReentrantLock[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
    }

    @Nullable
    @Override
    public OAuth2AuthorizedClient authorize(OAuth2AuthorizeRequest authorizeRequest) {
        ReentrantLock lock = lockFor(authorizeRequest);
        lock.lock();
        try {
            return delegate.authorize(authorizeRequest);
        } finally {
            lock.unlock();
        }
    }

    private ReentrantLock lockFor(OAuth2AuthorizeRequest authorizeRequest) {
        Authentication principal = authorizeRequest.getPrincipal();
        String principalName = principal == null ? "" : principal.getName();
        String key = authorizeRequest.getClientRegistrationId() + '\0' + principalName;
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }
}
