package com.msb.ecom.common.web.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringSecurityCurrentActorProviderTest {

    private final SpringSecurityCurrentActorProvider provider = new SpringSecurityCurrentActorProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentActorReadsJwtPrincipalFromSecurityContext() {
        Jwt jwt = new Jwt(
                "token-123",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of(
                        "sub", "user-123",
                        "email", "user@example.com",
                        "name", "User Name",
                        "email_verified", true));
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(jwt, null);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        CurrentActor actor = provider.currentActor();

        assertEquals("user-123", actor.subject());
        assertEquals("token-123", actor.accessToken());
        assertEquals("user@example.com", actor.email());
        assertEquals("User Name", actor.displayName());
        assertEquals(true, actor.emailVerified());
    }
}
