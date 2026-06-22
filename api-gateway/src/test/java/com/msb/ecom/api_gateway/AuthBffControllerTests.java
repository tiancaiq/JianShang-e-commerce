package com.msb.ecom.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
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

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginRedirectsToConfiguredOidcClient() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login").param("client", "marketplace"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/marketplace")));
    }

    @Test
    void loginRejectsUnknownClient() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login").param("client", "unknown"))
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
    void stateChangingRequestsRequireCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutEndpointIsAvailableWithCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(csrf())
                        .with(oidcLogin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Set-Cookie", containsString("JSESSIONID=;")));
    }
}
