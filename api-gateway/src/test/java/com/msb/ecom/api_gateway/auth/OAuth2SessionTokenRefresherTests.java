package com.msb.ecom.api_gateway.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuth2SessionTokenRefresherTests {

    private final ClientRegistrationRepository registrations = mock(ClientRegistrationRepository.class);
    private final OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    private final Instant now = Instant.parse("2026-07-07T05:55:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ClientRegistration registration = marketplaceRegistration();
    private final OAuth2AuthenticationToken authentication = authentication();

    @Test
    void refreshesExpiredAuthorizedClientAccessToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OAuth2SessionTokenRefresher refresher = new OAuth2SessionTokenRefresher(
                registrations,
                authorizedClients,
                builder,
                clock);

        when(registrations.findByRegistrationId("marketplace")).thenReturn(registration);
        when(authorizedClients.loadAuthorizedClient("marketplace", "keycloak-sub-1"))
                .thenReturn(authorizedClient(now.minusSeconds(10), "old-refresh-token"));

        server.expect(requestTo("http://localhost:8181/realms/msb-local/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("grant_type=refresh_token")))
                .andExpect(content().string(containsString("refresh_token=old-refresh-token")))
                .andRespond(withSuccess("""
                        {"access_token":"new-access-token","refresh_token":"new-refresh-token","expires_in":300}
                        """, MediaType.APPLICATION_JSON));

        refresher.refreshIfNecessary(authentication);

        ArgumentCaptor<OAuth2AuthorizedClient> captor = ArgumentCaptor.forClass(OAuth2AuthorizedClient.class);
        verify(authorizedClients).saveAuthorizedClient(captor.capture(), org.mockito.Mockito.eq(authentication));
        OAuth2AuthorizedClient refreshed = captor.getValue();
        assertThat(refreshed.getAccessToken().getTokenValue()).isEqualTo("new-access-token");
        assertThat(refreshed.getAccessToken().getExpiresAt()).isEqualTo(now.plusSeconds(300));
        assertThat(refreshed.getRefreshToken().getTokenValue()).isEqualTo("new-refresh-token");
        server.verify();
    }

    @Test
    void keepsCurrentAuthorizedClientWhenAccessTokenIsStillFresh() {
        OAuth2SessionTokenRefresher refresher = new OAuth2SessionTokenRefresher(
                registrations,
                authorizedClients,
                RestClient.builder(),
                clock);
        when(authorizedClients.loadAuthorizedClient("marketplace", "keycloak-sub-1"))
                .thenReturn(authorizedClient(now.plusSeconds(300), "refresh-token"));

        refresher.refreshIfNecessary(authentication);

        verify(authorizedClients, never()).saveAuthorizedClient(
                org.mockito.Mockito.any(OAuth2AuthorizedClient.class),
                org.mockito.Mockito.any());
    }

    private OAuth2AuthorizedClient authorizedClient(Instant accessTokenExpiresAt, String refreshToken) {
        return new OAuth2AuthorizedClient(
                registration,
                authentication.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "old-access-token",
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
