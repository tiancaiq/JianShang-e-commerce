package com.msb.ecom.api_gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class OAuth2SessionTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SessionTokenRefresher.class);
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final RestClient restClient;
    private final Clock clock;

    @Autowired
    public OAuth2SessionTokenRefresher(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            RestClient.Builder restClientBuilder) {
        this(clientRegistrationRepository, authorizedClientService, restClientBuilder, Clock.systemUTC());
    }

    OAuth2SessionTokenRefresher(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            RestClient.Builder restClientBuilder,
            Clock clock) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.authorizedClientService = authorizedClientService;
        this.restClient = restClientBuilder.build();
        this.clock = clock;
    }

    // Keeps the server-side OAuth client usable for token relay without exposing tokens to the browser.
    public void refreshIfNecessary(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)) {
            return;
        }

        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                oauth2Authentication.getAuthorizedClientRegistrationId(),
                oauth2Authentication.getName());
        if (authorizedClient == null
                || authorizedClient.getAccessToken() == null
                || authorizedClient.getRefreshToken() == null
                || !needsRefresh(authorizedClient.getAccessToken())) {
            return;
        }

        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(
                oauth2Authentication.getAuthorizedClientRegistrationId());
        if (registration == null) {
            return;
        }

        try {
            RefreshTokenResponse response = requestRefreshToken(registration, authorizedClient.getRefreshToken());
            if (response == null || response.accessToken() == null || response.expiresIn() <= 0) {
                log.warn("Identity provider returned an incomplete refresh response for client={}",
                        registration.getRegistrationId());
                return;
            }
            authorizedClientService.saveAuthorizedClient(
                    refreshedClient(registration, oauth2Authentication, authorizedClient, response),
                    oauth2Authentication);
        } catch (RestClientException exception) {
            log.warn("Could not refresh OAuth access token for client={}: {}",
                    registration.getRegistrationId(),
                    exception.getClass().getSimpleName());
        }
    }

    private boolean needsRefresh(OAuth2AccessToken accessToken) {
        Instant expiresAt = accessToken.getExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(Instant.now(clock).plus(REFRESH_SKEW));
    }

    private RefreshTokenResponse requestRefreshToken(
            ClientRegistration registration,
            OAuth2RefreshToken refreshToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", registration.getClientId());
        form.add("client_secret", registration.getClientSecret());
        form.add("refresh_token", refreshToken.getTokenValue());

        return restClient.post()
                .uri(registration.getProviderDetails().getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(RefreshTokenResponse.class);
    }

    private OAuth2AuthorizedClient refreshedClient(
            ClientRegistration registration,
            OAuth2AuthenticationToken authentication,
            OAuth2AuthorizedClient previousClient,
            RefreshTokenResponse response) {
        Instant issuedAt = Instant.now(clock);
        OAuth2RefreshToken refreshToken = response.refreshToken() == null || response.refreshToken().isBlank()
                ? previousClient.getRefreshToken()
                : new OAuth2RefreshToken(response.refreshToken(), issuedAt);
        return new OAuth2AuthorizedClient(
                registration,
                authentication.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        response.accessToken(),
                        issuedAt,
                        issuedAt.plusSeconds(response.expiresIn())),
                refreshToken);
    }

    private record RefreshTokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("refresh_token") String refreshToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn) {
    }
}
