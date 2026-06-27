package com.msb.ecom.common.web.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public class SpringSecurityCurrentActorProvider implements CurrentActorProvider {

    @Override
    public CurrentActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required.");
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("JWT authentication is required.");
        }

        return new CurrentActor(
                jwt.getSubject(),
                jwt.getTokenValue(),
                jwt.getClaimAsString("email"),
                displayName(jwt),
                Boolean.TRUE.equals(jwt.getClaim("email_verified")));
    }

    private String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        return jwt.getClaimAsString("email");
    }
}
