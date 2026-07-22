package com.msb.ecom.api_gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class NativeAuthService {

    private static final String MARKETPLACE_REGISTRATION_ID = "marketplace";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory;
    private final SecurityContextRepository securityContextRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String keycloakAdminBaseUri;
    private final String keycloakRealm;
    private final String adminClientId;
    private final String adminClientSecret;

    public NativeAuthService(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory,
            SecurityContextRepository securityContextRepository,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            @Value("${msb.gateway.auth.keycloak-admin-base-uri:http://localhost:8181}") String keycloakAdminBaseUri,
            @Value("${msb.gateway.auth.keycloak-realm:msb-local}") String keycloakRealm,
            @Value("${msb.gateway.auth.admin-client-id:msb-gateway-admin}") String adminClientId,
            @Value("${msb.gateway.auth.admin-client-secret:local-dev-only-change-me-gateway-admin}") String adminClientSecret) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.authorizedClientService = authorizedClientService;
        this.authorizedClientRepository = authorizedClientRepository;
        this.idTokenDecoderFactory = idTokenDecoderFactory;
        this.securityContextRepository = securityContextRepository;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.build();
        this.keycloakAdminBaseUri = keycloakAdminBaseUri;
        this.keycloakRealm = keycloakRealm;
        this.adminClientId = adminClientId;
        this.adminClientSecret = adminClientSecret;
    }

    // Authenticates marketplace credentials through Keycloak while keeping tokens server-side in the BFF session.
    public AuthBffController.SessionResponse login(
            NativeLoginRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        ClientRegistration registration = marketplaceRegistration();
        TokenResponse tokenResponse = requestPasswordToken(registration, requestBody.email(), requestBody.password());
        OAuth2AuthenticationToken authentication = authenticateSession(registration, tokenResponse, request, response);
        return AuthBffController.sessionResponse(authentication, null);
    }

    // Creates the Keycloak user through the gateway service account, then signs the user into the same BFF session.
    public AuthBffController.SessionResponse register(
            NativeRegisterRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        String adminToken = requestAdminToken();
        createKeycloakUser(adminToken, requestBody);
        return login(new NativeLoginRequest(requestBody.email(), requestBody.password()), request, response);
    }

    private ClientRegistration marketplaceRegistration() {
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(MARKETPLACE_REGISTRATION_ID);
        if (registration == null) {
            throw new NativeAuthException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTH_CLIENT_UNAVAILABLE",
                    "Marketplace authentication is not configured.");
        }
        return registration;
    }

    private TokenResponse requestPasswordToken(ClientRegistration registration, String email, String password) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", registration.getClientId());
        form.add("client_secret", registration.getClientSecret());
        form.add("username", email);
        form.add("password", password);
        form.add("scope", String.join(" ", registration.getScopes()));

        try {
            return restClient.post()
                    .uri(registration.getProviderDetails().getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (HttpClientErrorException.Unauthorized ex) {
            throw new NativeAuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
        } catch (HttpClientErrorException.BadRequest ex) {
            String keycloakError = keycloakError(ex);
            if ("unauthorized_client".equals(keycloakError)) {
                throw new NativeAuthException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "IDENTITY_PROVIDER_CONFIGURATION",
                        "Marketplace sign-in is not enabled in Keycloak. Enable direct access grants for the marketplace client.");
            }
            if ("invalid_grant".equals(keycloakError)) {
                throw new NativeAuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
            }
            throw new NativeAuthException(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR", "Identity provider rejected the sign-in request.");
        } catch (HttpClientErrorException ex) {
            throw new NativeAuthException(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR", "Identity provider rejected the sign-in request.");
        } catch (RestClientException ex) {
            throw new NativeAuthException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE", "Identity provider is unavailable.");
        }
    }

    private OAuth2AuthenticationToken authenticateSession(
            ClientRegistration registration,
            TokenResponse tokenResponse,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (tokenResponse == null || tokenResponse.idToken() == null || tokenResponse.accessToken() == null) {
            throw new NativeAuthException(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR", "Identity provider response was incomplete.");
        }

        Jwt jwt = idTokenDecoderFactory.createDecoder(registration).decode(tokenResponse.idToken());
        OidcIdToken idToken = new OidcIdToken(
                tokenResponse.idToken(),
                jwt.getIssuedAt(),
                jwt.getExpiresAt(),
                jwt.getClaims());

        var authorities = new ArrayList<SimpleGrantedAuthority>();
        for (String role : realmRoles(jwt.getClaims())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        var user = new DefaultOidcUser(authorities, idToken, IdTokenClaimNames.SUB);
        var authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), registration.getRegistrationId());

        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);

        OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(
                registration,
                authentication.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        tokenResponse.accessToken(),
                        Instant.now(),
                        Instant.now().plusSeconds(tokenResponse.expiresIn())),
                tokenResponse.refreshToken() == null
                        ? null
                        : new OAuth2RefreshToken(tokenResponse.refreshToken(), Instant.now()));
        authorizedClientService.saveAuthorizedClient(authorizedClient, authentication);
        authorizedClientRepository.saveAuthorizedClient(authorizedClient, authentication, request, response);
        return authentication;
    }

    private String requestAdminToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", adminClientId);
        form.add("client_secret", adminClientSecret);

        TokenResponse tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri("%s/realms/%s/protocol/openid-connect/token".formatted(keycloakAdminBaseUri, keycloakRealm))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (HttpClientErrorException.BadRequest
                 | HttpClientErrorException.Unauthorized
                 | HttpClientErrorException.Forbidden ex) {
            throw new NativeAuthException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "IDENTITY_PROVIDER_CONFIGURATION",
                    "Marketplace registration is not enabled in Keycloak. Check the gateway admin service account.");
        } catch (HttpClientErrorException ex) {
            throw new NativeAuthException(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR", "Identity provider rejected the registration request.");
        } catch (RestClientException ex) {
            throw new NativeAuthException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_PROVIDER_UNAVAILABLE", "Identity provider is unavailable.");
        }
        if (tokenResponse == null || tokenResponse.accessToken() == null) {
            throw new NativeAuthException(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR", "Identity provider admin token response was incomplete.");
        }
        return tokenResponse.accessToken();
    }

    private void createKeycloakUser(String adminToken, NativeRegisterRequest requestBody) {
        NameParts nameParts = nameParts(requestBody.displayName());
        Map<String, Object> user = Map.of(
                "username", requestBody.email(),
                "email", requestBody.email(),
                "enabled", true,
                "emailVerified", true,
                "firstName", nameParts.firstName(),
                "lastName", nameParts.lastName(),
                "attributes", Map.of("displayName", List.of(requestBody.displayName())),
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", requestBody.password(),
                        "temporary", false)));
        try {
            restClient.post()
                    .uri("%s/admin/realms/%s/users".formatted(keycloakAdminBaseUri, keycloakRealm))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict ex) {
            throw new NativeAuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "An account already exists for this email.");
        } catch (HttpClientErrorException ex) {
            throw new NativeAuthException(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR", "Identity provider rejected the registration request.");
        }
    }

    private NameParts nameParts(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return new NameParts("Marketplace", "User");
        }
        int split = normalized.indexOf(' ');
        if (split < 0) {
            return new NameParts(normalized, "User");
        }
        return new NameParts(normalized.substring(0, split), normalized.substring(split + 1));
    }

    private List<String> realmRoles(Map<String, Object> claims) {
        Object realmAccess = claims.get("realm_access");
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
                .toList();
    }

    private String keycloakError(HttpClientErrorException exception) {
        try {
            return objectMapper.readTree(exception.getResponseBodyAsString()).path("error").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    public record NativeLoginRequest(String email, String password) {
    }

    public record NativeRegisterRequest(String email, String password, String displayName) {
    }

    private record NameParts(String firstName, String lastName) {
    }

    public record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken,
            @com.fasterxml.jackson.annotation.JsonProperty("id_token") String idToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) {
    }
}
