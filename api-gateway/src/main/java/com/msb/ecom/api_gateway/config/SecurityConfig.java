package com.msb.ecom.api_gateway.config;

import com.msb.ecom.api_gateway.auth.LoginReturnUrl;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {

    private final List<String> allowedOrigins;
    private final String logoutRedirectUri;
    private final String loginSuccessBaseUri;

    public SecurityConfig(
            @Value("${msb.gateway.cors.allowed-origins:http://localhost:4200}") List<String> allowedOrigins,
            @Value("${msb.gateway.auth.logout-redirect-uri:http://localhost:4200/}") String logoutRedirectUri,
            @Value("${msb.gateway.auth.login-success-base-uri:http://localhost:4200}") String loginSuccessBaseUri) {
        this.allowedOrigins = allowedOrigins;
        this.logoutRedirectUri = logoutRedirectUri;
        this.loginSuccessBaseUri = loginSuccessBaseUri;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity httpSecurity,
            ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        HttpSessionCsrfTokenRepository csrfTokenRepository = new HttpSessionCsrfTokenRepository();
        csrfTokenRepository.setHeaderName("X-CSRF-TOKEN");

        XorCsrfTokenRequestAttributeHandler csrfRequestHandler = new XorCsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        return httpSecurity
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(new AntPathRequestMatcher("/api/v1/webhooks/business-verification")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/swagger-resources/**", "/aggregate/**",
                                "/actuator/health/**", "/fallbackRoute",
                                "/api/v1/webhooks/business-verification",
                                "/api/v1/categories", "/api/v1/categories/**",
                                "/api/v1/public/listings", "/api/v1/public/listings/**",
                                "/api/v1/public/listing-media/**",
                                "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/session",
                                "/api/v1/auth/callback/**", "/oauth2/authorization/**",
                                "/login/oauth2/code/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .authorizationRequestResolver(
                                        pkceAuthorizationRequestResolver(clientRegistrationRepository)))
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/api/v1/auth/callback/*"))
                        .successHandler(loginSuccessHandler()))
                .oauth2Client(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .build();
    }

    private OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(jakarta.servlet.http.HttpServletRequest request) {
                return withRegistrationAction(resolver.resolve(request), request.getParameter("kc_action"));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(
                    jakarta.servlet.http.HttpServletRequest request,
                    String clientRegistrationId) {
                return withRegistrationAction(
                        resolver.resolve(request, clientRegistrationId),
                        request.getParameter("kc_action"));
            }
        };
    }

    private OAuth2AuthorizationRequest withRegistrationAction(
            OAuth2AuthorizationRequest authorizationRequest,
            String keycloakAction) {
        if (authorizationRequest == null || !"register".equals(keycloakAction)) {
            return authorizationRequest;
        }

        Map<String, Object> additionalParameters =
                new LinkedHashMap<>(authorizationRequest.getAdditionalParameters());
        additionalParameters.put("kc_action", "register");
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(additionalParameters)
                .build();
    }

    private AuthenticationSuccessHandler loginSuccessHandler() {
        return (request, response, authentication) -> {
            String returnUrl = "/";
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object candidate = session.getAttribute(LoginReturnUrl.SESSION_ATTRIBUTE);
                session.removeAttribute(LoginReturnUrl.SESSION_ATTRIBUTE);
                if (candidate instanceof String value) {
                    returnUrl = LoginReturnUrl.sanitize(value).orElse("/");
                }
            }
            response.sendRedirect(LoginReturnUrl.joinWithBaseUri(loginSuccessBaseUri, returnUrl));
        };
    }

    private OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler(
            ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler successHandler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        successHandler.setPostLogoutRedirectUri(logoutRedirectUri);
        successHandler.setDefaultTargetUrl(logoutRedirectUri);
        return successHandler;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Correlation-Id", "X-CSRF-TOKEN",
                "Idempotency-Key", "If-Match", "X-MSB-Signature"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
