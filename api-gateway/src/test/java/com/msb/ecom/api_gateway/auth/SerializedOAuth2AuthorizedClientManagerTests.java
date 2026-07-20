package com.msb.ecom.api_gateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SerializedOAuth2AuthorizedClientManagerTests {

    private final Instant now = Instant.parse("2026-07-19T08:00:00Z");
    private final ClientRegistration registration = marketplaceRegistration();
    private final OAuth2AuthenticationToken authentication = authentication();

    @Test
    void concurrentAndRepeatedAuthorizationRotatesRefreshTokenOnlyOnce() throws Exception {
        InMemoryClientRegistrationRepository registrations =
                new InMemoryClientRegistrationRepository(registration);
        InMemoryOAuth2AuthorizedClientService authorizedClients =
                new InMemoryOAuth2AuthorizedClientService(registrations);
        authorizedClients.saveAuthorizedClient(
                authorizedClient(now.minusSeconds(1), "old-refresh-token"),
                authentication);

        AtomicInteger refreshCount = new AtomicInteger();
        OAuth2AuthorizedClientProvider provider = context -> {
            OAuth2AuthorizedClient current = context.getAuthorizedClient();
            if (current == null || current.getAccessToken().getExpiresAt().isAfter(now)) {
                return null;
            }
            refreshCount.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return authorizedClient(now.plusSeconds(300), "rotated-refresh-token");
        };

        DefaultOAuth2AuthorizedClientManager delegate = new DefaultOAuth2AuthorizedClientManager(
                registrations,
                new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(authorizedClients));
        delegate.setAuthorizedClientProvider(provider);
        SerializedOAuth2AuthorizedClientManager manager =
                new SerializedOAuth2AuthorizedClientManager(delegate);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OAuth2AuthorizedClient> first = executor.submit(
                    () -> authorizeTogether(manager, ready, start));
            Future<OAuth2AuthorizedClient> second = executor.submit(
                    () -> authorizeTogether(manager, ready, start));

            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS).getRefreshToken().getTokenValue())
                    .isEqualTo("rotated-refresh-token");
            assertThat(second.get(2, TimeUnit.SECONDS).getRefreshToken().getTokenValue())
                    .isEqualTo("rotated-refresh-token");
            assertThat(manager.authorize(authorizeRequest()).getRefreshToken().getTokenValue())
                    .isEqualTo("rotated-refresh-token");
        } finally {
            executor.shutdownNow();
        }

        assertThat(refreshCount).hasValue(1);
        assertThat(authorizedClients.<OAuth2AuthorizedClient>loadAuthorizedClient(
                        "marketplace",
                        "keycloak-sub-1")
                .getRefreshToken()
                .getTokenValue())
                .isEqualTo("rotated-refresh-token");
    }

    private OAuth2AuthorizedClient authorizeTogether(
            SerializedOAuth2AuthorizedClientManager manager,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
        return manager.authorize(authorizeRequest());
    }

    private OAuth2AuthorizeRequest authorizeRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        return OAuth2AuthorizeRequest.withClientRegistrationId("marketplace")
                .principal(authentication)
                .attributes(attributes -> {
                    attributes.put(HttpServletRequest.class.getName(), request);
                    attributes.put(HttpServletResponse.class.getName(), response);
                })
                .build();
    }

    private OAuth2AuthorizedClient authorizedClient(Instant accessTokenExpiresAt, String refreshToken) {
        return new OAuth2AuthorizedClient(
                registration,
                authentication.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "access-token-" + refreshToken,
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
