package com.msb.ecom.api_gateway;

import com.msb.ecom.api_gateway.auth.LoginReturnUrl;
import com.msb.ecom.api_gateway.auth.OAuth2SessionTokenRefresher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class AuthBffControllerTests {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private OAuth2AuthorizedClientService authorizedClientService;

    @MockitoBean
    private OAuth2SessionTokenRefresher tokenRefresher;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginRedirectsToConfiguredOidcClient() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login").param("client", "marketplace"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/marketplace")));

        mockMvc.perform(get("/api/v1/auth/login").param("client", "seller-portal"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/seller-portal")));

        mockMvc.perform(get("/api/v1/auth/login").param("client", "admin-portal"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/admin-portal")));
    }

    @Test
    void loginStoresSafeRelativeReturnUrl() throws Exception {
        var result = mockMvc.perform(get("/api/v1/auth/login")
                        .param("client", "seller-portal")
                        .param("returnUrl", "/seller/business/apply?draft=1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/seller-portal")))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
        assertThat(result.getRequest().getSession(false).getAttribute(LoginReturnUrl.SESSION_ATTRIBUTE))
                .isEqualTo("/seller/business/apply?draft=1");
    }

    @Test
    void loginPopupStoresSafeReturnUrlAndPopupMode() throws Exception {
        var result = mockMvc.perform(get("/api/v1/auth/login")
                        .param("client", "marketplace")
                        .param("returnUrl", "/account/profile")
                        .param("mode", "popup"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/marketplace")))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
        assertThat(result.getRequest().getSession(false).getAttribute(LoginReturnUrl.SESSION_ATTRIBUTE))
                .isEqualTo("/account/profile");
        assertThat(result.getRequest().getSession(false).getAttribute(LoginReturnUrl.POPUP_SESSION_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void loginWithGoogleProviderRedirectsWithProviderHint() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login")
                        .param("client", "marketplace")
                        .param("provider", "google")
                        .param("mode", "popup"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/marketplace")))
                .andExpect(header().string("Location", containsString("kc_idp_hint=google")));
    }

    @Test
    void registerWithGoogleProviderRedirectsWithProviderHintAndRegistrationAction() throws Exception {
        mockMvc.perform(get("/api/v1/auth/register")
                        .param("client", "marketplace")
                        .param("provider", "google")
                        .param("mode", "popup"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/marketplace")))
                .andExpect(header().string("Location", containsString("kc_action=register")))
                .andExpect(header().string("Location", containsString("kc_idp_hint=google")));
    }

    @Test
    void loginRejectsUnknownExternalProvider() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login")
                        .param("client", "marketplace")
                        .param("provider", "unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerRejectsUnknownExternalProvider() throws Exception {
        mockMvc.perform(get("/api/v1/auth/register")
                        .param("client", "marketplace")
                        .param("provider", "unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginIgnoresUnsafeReturnUrl() throws Exception {
        var result = mockMvc.perform(get("/api/v1/auth/login")
                        .param("client", "marketplace")
                        .param("returnUrl", "https://evil.example/account"))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
        assertThat(result.getRequest().getSession(false).getAttribute(LoginReturnUrl.SESSION_ATTRIBUTE))
                .isNull();
    }

    @Test
    void loginRejectsUnknownClient() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login").param("client", "unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerRedirectsToConfiguredOidcClientWithRegistrationAction() throws Exception {
        var result = mockMvc.perform(get("/api/v1/auth/register")
                        .param("client", "marketplace")
                        .param("returnUrl", "/account/profile"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/marketplace")))
                .andExpect(header().string("Location", containsString("kc_action=register")))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
        assertThat(result.getRequest().getSession(false).getAttribute(LoginReturnUrl.SESSION_ATTRIBUTE))
                .isEqualTo("/account/profile");
    }

    @Test
    void registerPopupStoresPopupMode() throws Exception {
        var result = mockMvc.perform(get("/api/v1/auth/register")
                        .param("client", "marketplace")
                        .param("mode", "popup"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/marketplace")))
                .andExpect(header().string("Location", containsString("kc_action=register")))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNotNull();
        assertThat(result.getRequest().getSession(false).getAttribute(LoginReturnUrl.POPUP_SESSION_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void registerRejectsUnknownClient() throws Exception {
        mockMvc.perform(get("/api/v1/auth/register").param("client", "unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void oidcAuthorizationRequestUsesPkce() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/marketplace"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("code_challenge=")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")));
    }

    @Test
    void oidcAuthorizationRequestCanOpenKeycloakRegistration() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/marketplace").param("kc_action", "register"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("kc_action=register")))
                .andExpect(header().string("Location", containsString("code_challenge=")));
    }

    @Test
    void oidcAuthorizationRequestCanHintGoogleProvider() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/marketplace").param("kc_idp_hint", "google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("kc_idp_hint=google")))
                .andExpect(header().string("Location", containsString("code_challenge=")));
    }

    @Test
    void oidcAuthorizationRequestIgnoresUnknownProviderHint() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/marketplace").param("kc_idp_hint", "unknown"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.not(containsString("kc_idp_hint=unknown"))))
                .andExpect(header().string("Location", containsString("code_challenge=")));
    }

    @Test
    void anonymousSessionReturnsCsrfButNoOauthTokens() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.csrf.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrf.token").isString())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void authenticatedSessionReturnsSafeUserSummaryOnly() throws Exception {
        mockMvc.perform(get("/api/v1/auth/session").with(oidcLogin()
                        .idToken(idToken -> idToken
                                .subject("keycloak-sub-123")
                                .claim("email", "alex@example.com")
                                .claim("name", "Alex Buyer")
                                .claim("realm_access", Map.of("roles", List.of("BUYER"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.subject").value("keycloak-sub-123"))
                .andExpect(jsonPath("$.user.email").value("alex@example.com"))
                .andExpect(jsonPath("$.user.displayName").value("Alex Buyer"))
                .andExpect(jsonPath("$.user.roles[0]").value("BUYER"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void unusableAuthorizedClientClearsPhantomSessionAuthentication() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(tokenRefresher.refreshIfNecessary(any()))
                .thenReturn(OAuth2SessionTokenRefresher.RefreshResult.SESSION_UNAVAILABLE);

        mockMvc.perform(get("/api/v1/auth/session")
                        .session(session)
                        .with(oidcLogin()
                                .idToken(idToken -> idToken
                                        .subject("keycloak-sub-123")
                                        .claim("email", "alex@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.csrf.token").isString());

        mockMvc.perform(get("/api/v1/users/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stateChangingRequestsRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/native/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alex@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedApiRequestsReturnUnauthorizedInsteadOfLoginRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void logoutEndpointIsAvailableWithCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(csrf())
                        .with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        containsString("http://localhost:8181/realms/msb-local/protocol/openid-connect/logout")))
                .andExpect(header().string("Location", containsString("id_token_hint=")))
                .andExpect(header().string("Location", containsString("post_logout_redirect_uri=http://localhost:4200/")))
                .andExpect(header().string("Location", containsString("signedOut%3D1")))
                .andExpect(header().string("Set-Cookie", containsString("JSESSIONID=;")));

        verify(authorizedClientService).removeAuthorizedClient(anyString(), anyString());
    }

    @Test
    void logoutRedirectsSellerPortalBackToSellerLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .param("client", "seller-portal")
                        .with(csrf())
                        .with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        containsString("post_logout_redirect_uri=http://localhost:4200/login")))
                .andExpect(header().string("Location", containsString("client%3Dseller-portal")))
                .andExpect(header().string("Location", containsString("signedOut%3D1")));
    }

    @Test
    void logoutRedirectsAdminPortalBackToAdminLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .param("client", "admin-portal")
                        .with(csrf())
                        .with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        containsString("post_logout_redirect_uri=http://localhost:4200/login")))
                .andExpect(header().string("Location", containsString("client%3Dadmin-portal")))
                .andExpect(header().string("Location", containsString("signedOut%3D1")));
    }

    @Test
    void logoutIgnoresUnknownClientHint() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .param("client", "unknown")
                        .with(csrf())
                        .with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("post_logout_redirect_uri=http://localhost:4200/")))
                .andExpect(header().string("Location", containsString("signedOut%3D1")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.not(containsString("client%3Dunknown"))));
    }
}
