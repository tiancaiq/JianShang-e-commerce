package com.msb.ecom.api_gateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class AuthBffController {

    private static final Set<String> SUPPORTED_CLIENTS = Set.of("marketplace", "seller-portal", "admin-portal");

    @GetMapping("/api/v1/auth/login")
    public ResponseEntity<Void> login(
            @RequestParam(defaultValue = "marketplace") String client,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request) {
        return authorizationRedirect(client, returnUrl, request, false);
    }

    @GetMapping("/api/v1/auth/register")
    public ResponseEntity<Void> register(
            @RequestParam(defaultValue = "marketplace") String client,
            @RequestParam(required = false) String returnUrl,
            HttpServletRequest request) {
        return authorizationRedirect(client, returnUrl, request, true);
    }

    private ResponseEntity<Void> authorizationRedirect(
            String client,
            String returnUrl,
            HttpServletRequest request,
            boolean registration) {
        if (!SUPPORTED_CLIENTS.contains(client)) {
            return ResponseEntity.notFound().build();
        }

        LoginReturnUrl.sanitize(returnUrl).ifPresent(safeReturnUrl -> {
            HttpSession session = request.getSession(true);
            session.setAttribute(LoginReturnUrl.SESSION_ATTRIBUTE, safeReturnUrl);
        });

        var builder = ServletUriComponentsBuilder.fromContextPath(request)
                .path("/oauth2/authorization/{client}");
        if (registration) {
            builder.queryParam("kc_action", "register");
        }

        URI authorizationUri = builder.build(client);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(authorizationUri)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/api/v1/auth/session")
    public ResponseEntity<SessionResponse> session(Authentication authentication, CsrfToken csrfToken) {
        SessionResponse response = new SessionResponse(
                isAuthenticated(authentication),
                user(authentication),
                csrf(csrfToken));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication instanceof OAuth2AuthenticationToken;
    }

    private SessionUser user(Authentication authentication) {
        if (!isAuthenticated(authentication) || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return null;
        }

        return new SessionUser(
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                oidcUser.getFullName(),
                realmRoles(oidcUser),
                expiresAt(oidcUser));
    }

    private List<String> realmRoles(OidcUser oidcUser) {
        Object realmAccess = oidcUser.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            return List.of();
        }
        Object roles = realmAccessMap.get("roles");
        if (!(roles instanceof List<?> roleList)) {
            return List.of();
        }
        return roleList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .sorted()
                .toList();
    }

    private Instant expiresAt(OidcUser oidcUser) {
        return oidcUser.getExpiresAt();
    }

    private CsrfSummary csrf(CsrfToken csrfToken) {
        if (csrfToken == null) {
            return null;
        }
        return new CsrfSummary(csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken());
    }

    public record SessionResponse(boolean authenticated, SessionUser user, CsrfSummary csrf) {
    }

    public record SessionUser(String subject, String email, String displayName, List<String> roles, Instant expiresAt) {
    }

    public record CsrfSummary(String headerName, String parameterName, String token) {
    }
}
