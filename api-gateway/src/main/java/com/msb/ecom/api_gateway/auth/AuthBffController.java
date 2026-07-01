package com.msb.ecom.api_gateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
public class AuthBffController {

    private static final Set<String> SUPPORTED_CLIENTS = Set.of("marketplace", "seller-portal", "admin-portal");
    private static final Set<String> SUPPORTED_EXTERNAL_PROVIDERS = Set.of("google");
    private final NativeAuthService nativeAuthService;

    public AuthBffController(NativeAuthService nativeAuthService) {
        this.nativeAuthService = nativeAuthService;
    }

    @GetMapping("/api/v1/auth/login")
    public ResponseEntity<Void> login(
            @RequestParam(defaultValue = "marketplace") String client,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String provider,
            HttpServletRequest request) {
        return authorizationRedirect(client, returnUrl, mode, provider, request, false);
    }

    @GetMapping("/api/v1/auth/register")
    public ResponseEntity<Void> register(
            @RequestParam(defaultValue = "marketplace") String client,
            @RequestParam(required = false) String returnUrl,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String provider,
            HttpServletRequest request) {
        return authorizationRedirect(client, returnUrl, mode, provider, request, true);
    }

    @PostMapping("/api/v1/auth/native/login")
    public ResponseEntity<SessionResponse> nativeLogin(
            @RequestBody NativeAuthService.NativeLoginRequest requestBody,
            CsrfToken csrfToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        return noStore(withCsrf(nativeAuthService.login(requestBody, request, response), csrfToken));
    }

    @PostMapping("/api/v1/auth/native/register")
    public ResponseEntity<SessionResponse> nativeRegister(
            @RequestBody NativeAuthService.NativeRegisterRequest requestBody,
            CsrfToken csrfToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        return noStore(withCsrf(nativeAuthService.register(requestBody, request, response), csrfToken));
    }

    private ResponseEntity<Void> authorizationRedirect(
            String client,
            String returnUrl,
            String mode,
            String provider,
            HttpServletRequest request,
            boolean registration) {
        if (!SUPPORTED_CLIENTS.contains(client)) {
            return ResponseEntity.notFound().build();
        }
        if (provider != null && !SUPPORTED_EXTERNAL_PROVIDERS.contains(provider)) {
            return ResponseEntity.notFound().build();
        }

        Optional<String> safeReturnUrl = LoginReturnUrl.sanitize(returnUrl);
        boolean popup = "popup".equals(mode);
        HttpSession session = request.getSession(safeReturnUrl.isPresent() || popup);
        if (session != null) {
            safeReturnUrl.ifPresentOrElse(
                    value -> session.setAttribute(LoginReturnUrl.SESSION_ATTRIBUTE, value),
                    () -> session.removeAttribute(LoginReturnUrl.SESSION_ATTRIBUTE));
            if (popup) {
                session.setAttribute(LoginReturnUrl.POPUP_SESSION_ATTRIBUTE, Boolean.TRUE);
            } else {
                session.removeAttribute(LoginReturnUrl.POPUP_SESSION_ATTRIBUTE);
            }
        }

        var builder = ServletUriComponentsBuilder.fromContextPath(request)
                .path("/oauth2/authorization/{client}");
        if (registration) {
            builder.queryParam("kc_action", "register");
        }
        if (provider != null) {
            builder.queryParam("kc_idp_hint", provider);
        }

        URI authorizationUri = builder.build(client);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(authorizationUri)
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @GetMapping("/api/v1/auth/session")
    public ResponseEntity<SessionResponse> session(Authentication authentication, CsrfToken csrfToken) {
        SessionResponse response = sessionResponse(authentication, csrfToken);

        return noStore(response);
    }

    @ExceptionHandler(NativeAuthException.class)
    public ResponseEntity<NativeAuthErrorResponse> nativeAuthError(NativeAuthException exception) {
        return ResponseEntity.status(exception.status())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new NativeAuthErrorResponse(exception.code(), exception.getMessage()));
    }

    private ResponseEntity<SessionResponse> noStore(SessionResponse response) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }

    private SessionResponse withCsrf(SessionResponse response, CsrfToken csrfToken) {
        return new SessionResponse(response.authenticated(), response.user(), csrf(csrfToken));
    }

    public static SessionResponse sessionResponse(Authentication authentication, CsrfToken csrfToken) {
        return new SessionResponse(
                isAuthenticated(authentication),
                user(authentication),
                csrf(csrfToken));
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication instanceof OAuth2AuthenticationToken;
    }

    private static SessionUser user(Authentication authentication) {
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

    private static List<String> realmRoles(OidcUser oidcUser) {
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

    private static Instant expiresAt(OidcUser oidcUser) {
        return oidcUser.getExpiresAt();
    }

    private static CsrfSummary csrf(CsrfToken csrfToken) {
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

    public record NativeAuthErrorResponse(String code, String message) {
    }
}
