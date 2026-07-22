package com.msb.ecom.api_gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NativeAuthServiceTests {

    private final ClientRegistrationRepository registrations = mock(ClientRegistrationRepository.class);
    private final OAuth2AuthorizedClientService authorizedClients = mock(OAuth2AuthorizedClientService.class);
    private final OAuth2AuthorizedClientRepository authorizedClientRepository = mock(OAuth2AuthorizedClientRepository.class);
    private final JwtDecoderFactory<ClientRegistration> decoders = mock(JwtDecoderFactory.class);
    private final SecurityContextRepository securityContexts = mock(SecurityContextRepository.class);
    private final HttpServletRequest servletRequest = mock(HttpServletRequest.class);
    private final HttpServletResponse servletResponse = mock(HttpServletResponse.class);

    @Test
    void loginMapsDisabledDirectAccessGrantToConfigurationError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NativeAuthService service = service(builder);

        server.expect(requestTo("http://localhost:8181/realms/msb-local/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"unauthorized_client\"}"));

        assertThatThrownBy(() -> service.login(
                        new NativeAuthService.NativeLoginRequest("alex@example.com", "secret"),
                        servletRequest,
                        servletResponse))
                .isInstanceOfSatisfying(NativeAuthException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("IDENTITY_PROVIDER_CONFIGURATION");
                    assertThat(exception.getMessage()).contains("direct access grants");
                });

        server.verify();
    }

    @Test
    void loginMapsInvalidGrantToInvalidCredentials() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NativeAuthService service = service(builder);

        server.expect(requestTo("http://localhost:8181/realms/msb-local/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> service.login(
                        new NativeAuthService.NativeLoginRequest("alex@example.com", "wrong"),
                        servletRequest,
                        servletResponse))
                .isInstanceOfSatisfying(NativeAuthException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.code()).isEqualTo("INVALID_CREDENTIALS");
                });

        server.verify();
    }

    @Test
    void registerMapsDuplicateEmailToConflict() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NativeAuthService service = service(builder);

        server.expect(requestTo("http://localhost:8181/realms/msb-local/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"admin-token\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("http://localhost:8181/admin/realms/msb-local/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> service.register(
                        new NativeAuthService.NativeRegisterRequest("alex@example.com", "secret", "Alex Buyer"),
                        servletRequest,
                        servletResponse))
                .isInstanceOfSatisfying(NativeAuthException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
                    assertThat(exception.getMessage()).contains("already exists");
                });

        server.verify();
    }

    @Test
    void registerMapsAdminTokenFailureToConfigurationError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NativeAuthService service = service(builder);

        server.expect(requestTo("http://localhost:8181/realms/msb-local/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> service.register(
                        new NativeAuthService.NativeRegisterRequest("alex@example.com", "secret", "Alex Buyer"),
                        servletRequest,
                        servletResponse))
                .isInstanceOfSatisfying(NativeAuthException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("IDENTITY_PROVIDER_CONFIGURATION");
                    assertThat(exception.getMessage()).contains("gateway admin service account");
                });

        server.verify();
    }

    private NativeAuthService service(RestClient.Builder builder) {
        when(registrations.findByRegistrationId("marketplace")).thenReturn(marketplaceRegistration());
        return new NativeAuthService(
                registrations,
                authorizedClients,
                authorizedClientRepository,
                decoders,
                securityContexts,
                new ObjectMapper(),
                builder,
                "http://localhost:8181",
                "msb-local",
                "msb-gateway-admin",
                "test-gateway-admin");
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
