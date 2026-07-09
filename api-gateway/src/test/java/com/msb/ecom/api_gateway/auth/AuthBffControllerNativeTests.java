package com.msb.ecom.api_gateway.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.MethodParameter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthBffControllerNativeTests {

    @Test
    void nativeLoginReturnsBffSessionWithoutBrowserTokens() throws Exception {
        mockMvc(new StubNativeAuthService(false)).perform(post("/api/v1/auth/native/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alex@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.email").value("alex@example.com"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.idToken").doesNotExist());
    }

    @Test
    void nativeRegisterReturnsBffSessionWithoutBrowserTokens() throws Exception {
        mockMvc(new StubNativeAuthService(false)).perform(post("/api/v1/auth/native/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alex Buyer\",\"email\":\"alex@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.displayName").value("Alex Buyer"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void nativeAuthErrorsUseJsonBody() throws Exception {
        mockMvc(new StubNativeAuthService(true)).perform(post("/api/v1/auth/native/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alex@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect."));
    }

    private MockMvc mockMvc(NativeAuthService nativeAuthService) {
        return MockMvcBuilders.standaloneSetup(new AuthBffController(
                        nativeAuthService,
                        new OAuth2SessionTokenRefresher(null, null, RestClient.builder())))
                .setCustomArgumentResolvers(new NullCsrfTokenResolver())
                .build();
    }

    private static final class NullCsrfTokenResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return CsrfToken.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return null;
        }
    }

    private static final class StubNativeAuthService extends NativeAuthService {
        private final boolean failLogin;

        private StubNativeAuthService(boolean failLogin) {
            super(null, null, null, null, null, RestClient.builder(), "", "", "", "");
            this.failLogin = failLogin;
        }

        @Override
        public AuthBffController.SessionResponse login(
                NativeLoginRequest requestBody,
                HttpServletRequest request,
                HttpServletResponse response) {
            if (failLogin) {
                throw new NativeAuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
            }
            return session();
        }

        @Override
        public AuthBffController.SessionResponse register(
                NativeRegisterRequest requestBody,
                HttpServletRequest request,
                HttpServletResponse response) {
            return session();
        }

        private AuthBffController.SessionResponse session() {
            return new AuthBffController.SessionResponse(
                    true,
                    new AuthBffController.SessionUser(
                            "keycloak-sub-123",
                            "alex@example.com",
                            "Alex Buyer",
                            List.of("BUYER"),
                            Instant.parse("2026-07-01T12:00:00Z")),
                    null);
        }
    }
}
