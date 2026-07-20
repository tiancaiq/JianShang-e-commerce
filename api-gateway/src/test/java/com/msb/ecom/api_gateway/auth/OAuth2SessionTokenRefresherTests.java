package com.msb.ecom.api_gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2SessionTokenRefresherTests {

    private final OAuth2AuthorizedClientManager authorizedClientManager = mock(OAuth2AuthorizedClientManager.class);
    private final OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    private final Instant now = Instant.parse("2026-07-19T08:00:00Z");
    private final ClientRegistration registration = marketplaceRegistration();
    private final OAuth2AuthenticationToken authentication = authentication();
    private final OAuth2SessionTokenRefresher refresher = new OAuth2SessionTokenRefresher(
            authorizedClientManager,
            authorizedClients);

    @Test
    void delegatesSessionRefreshToTheSharedAuthorizedClientManager() {
        OAuth2AuthorizedClient currentClient = authorizedClient(now.plusSeconds(300), "rotated-refresh-token");
        when(authorizedClients.loadAuthorizedClient("marketplace", "keycloak-sub-1"))
                .thenReturn(currentClient);
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenReturn(currentClient);

        OAuth2SessionTokenRefresher.RefreshResult result = refresher.refreshIfNecessary(authentication);

        assertThat(result).isEqualTo(OAuth2SessionTokenRefresher.RefreshResult.SESSION_USABLE);
        verify(authorizedClientManager).authorize(any(OAuth2AuthorizeRequest.class));
        verify(authorizedClients, never()).removeAuthorizedClient("marketplace", "keycloak-sub-1");
    }

    @Test
    void reportsMissingAuthorizedClientAsAnUnusableSession() {
        when(authorizedClients.loadAuthorizedClient("marketplace", "keycloak-sub-1"))
                .thenReturn(null);

        OAuth2SessionTokenRefresher.RefreshResult result = refresher.refreshIfNecessary(authentication);

        assertThat(result).isEqualTo(OAuth2SessionTokenRefresher.RefreshResult.SESSION_UNAVAILABLE);
        verify(authorizedClientManager, never()).authorize(any(OAuth2AuthorizeRequest.class));
    }

    @Test
    void removesStaleAuthorizedClientAfterInvalidGrant() {
        when(authorizedClients.loadAuthorizedClient("marketplace", "keycloak-sub-1"))
                .thenReturn(authorizedClient(now.minusSeconds(10), "stale-refresh-token"));
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenThrow(new ClientAuthorizationException(
                        new OAuth2Error("invalid_grant", "Refresh token is no longer valid.", null),
                        "marketplace"));

        OAuth2SessionTokenRefresher.RefreshResult result = refresher.refreshIfNecessary(authentication);

        assertThat(result).isEqualTo(OAuth2SessionTokenRefresher.RefreshResult.SESSION_UNAVAILABLE);
        verify(authorizedClients).removeAuthorizedClient("marketplace", "keycloak-sub-1");
    }

    @Test
    void propagatesIdentityProviderFailuresOtherThanInvalidGrant() {
        when(authorizedClients.loadAuthorizedClient("marketplace", "keycloak-sub-1"))
                .thenReturn(authorizedClient(now.minusSeconds(10), "refresh-token"));
        when(authorizedClientManager.authorize(any(OAuth2AuthorizeRequest.class)))
                .thenThrow(new ClientAuthorizationException(
                        new OAuth2Error("temporarily_unavailable", "Identity provider unavailable.", null),
                        "marketplace"));

        assertThatThrownBy(() -> refresher.refreshIfNecessary(authentication))
                .isInstanceOf(ClientAuthorizationException.class)
                .hasMessageContaining("temporarily_unavailable");
        verify(authorizedClients, never()).removeAuthorizedClient("marketplace", "keycloak-sub-1");
    }

    private OAuth2AuthorizedClient authorizedClient(Instant accessTokenExpiresAt, String refreshToken) {
        return new OAuth2AuthorizedClient(
                registration,
                authentication.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access-token",
                        now.minusSeconds(300),
                        accessTokenExpiresAt),
                new OAuth2RefreshToken(refreshToken, now.minusSeconds(300)));
    }

    private OAuth2AuthenticationToken authentication() {
        OidcIdToken idToken = new OidcIdToken(
                "id-token",
                now.minusSeconds(300),
                now.plusSeconds(300),
                Map.of(IdTokenClaimNames.SUB, "keycloak-sub-1"));
        DefaultOidcUser user = new DefaultOidcUser(List.of(), idToken, IdTokenClaimNames.SUB);
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "marketplace");
    }

    private ClientRegistration marketplaceRegistration() {
        return ClientRegistration.withRegistrationId("marketplace")
                .clientId("msb-marketplace")
                .clientSecret("test-marketplace")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/api/v1/auth/callback/{registrationId}")
                .authorizationUri("http://localhost:8181/realms/msb-local/protocol/openid-connect/auth")
                .tokenUri("http://localhost:8181/realms/msb-local/protocol/openid-connect/token")
                .jwkSetUri("http://localhost:8181/realms/msb-local/protocol/openid-connect/certs")
                .userNameAttributeName("sub")
                .scope("openid", "profile", "email", "roles")
                .build();
    }
}
