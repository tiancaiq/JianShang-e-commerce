package com.msb.ecom.api_gateway.config;

import com.msb.ecom.api_gateway.auth.LoginReturnUrl;
import com.msb.ecom.api_gateway.auth.SerializedOAuth2AuthorizedClientManager;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class SecurityConfig {

    private static final Set<String> SUPPORTED_LOGOUT_CLIENTS = Set.of("marketplace", "seller-portal", "admin-portal");

    private final List<String> allowedOrigins;
    private final String logoutRedirectUri;
    private final String loginSuccessBaseUri;
    private final String oidcLogoutUri;

    public SecurityConfig(
            @Value("${msb.gateway.cors.allowed-origins:http://localhost:4200}") List<String> allowedOrigins,
            @Value("${msb.gateway.auth.logout-redirect-uri:http://localhost:4200/}") String logoutRedirectUri,
            @Value("${msb.gateway.auth.login-success-base-uri:http://localhost:4200}") String loginSuccessBaseUri,
            @Value("${msb.gateway.auth.oidc-logout-uri}") String oidcLogoutUri) {
        this.allowedOrigins = allowedOrigins;
        this.logoutRedirectUri = logoutRedirectUri;
        this.loginSuccessBaseUri = loginSuccessBaseUri;
        this.oidcLogoutUri = oidcLogoutUri;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity httpSecurity,
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) throws Exception {
        HttpSessionCsrfTokenRepository csrfTokenRepository = new HttpSessionCsrfTokenRepository();
        csrfTokenRepository.setHeaderName("X-CSRF-TOKEN");

        XorCsrfTokenRequestAttributeHandler csrfRequestHandler = new XorCsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        return httpSecurity
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/v1/webhooks/business-verification"),
                                new AntPathRequestMatcher(
                                        "/api/v1/webhooks/payments/STRIPE_TEST_V1", "POST")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/public/**"))
                        .permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/stores/*"))
                        .permitAll()
                        .requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/swagger-resources/**", "/aggregate/**",
                                "/actuator/health/**", "/fallbackRoute",
                                "/api/v1/webhooks/business-verification",
                                "/api/v1/webhooks/payments/STRIPE_TEST_V1",
                                "/api/v1/categories", "/api/v1/categories/**",
                                "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/session",
                                "/api/v1/auth/native/login", "/api/v1/auth/native/register",
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
                .securityContext(securityContext -> securityContext.securityContextRepository(securityContextRepository()))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**")))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(authorizedClientService))
                        .clearAuthentication(true)
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public OAuth2AuthorizedClientManager oauth2AuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {
        DefaultOAuth2AuthorizedClientManager delegate = new DefaultOAuth2AuthorizedClientManager(
                clientRegistrationRepository,
                authorizedClientRepository);
        return new SerializedOAuth2AuthorizedClientManager(delegate);
    }

    @Bean
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        return new OidcIdTokenDecoderFactory();
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
                return withAuthHints(
                        resolver.resolve(request),
                        request.getParameter("kc_action"),
                        request.getParameter("kc_idp_hint"));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(
                    jakarta.servlet.http.HttpServletRequest request,
                    String clientRegistrationId) {
                return withAuthHints(
                        resolver.resolve(request, clientRegistrationId),
                        request.getParameter("kc_action"),
                        request.getParameter("kc_idp_hint"));
            }
        };
    }

    private OAuth2AuthorizationRequest withAuthHints(
            OAuth2AuthorizationRequest authorizationRequest,
            String keycloakAction,
            String identityProviderHint) {
        OAuth2AuthorizationRequest withRegistrationAction =
                withRegistrationAction(authorizationRequest, keycloakAction);
        return withIdentityProviderHint(withRegistrationAction, identityProviderHint);
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

    private OAuth2AuthorizationRequest withIdentityProviderHint(
            OAuth2AuthorizationRequest authorizationRequest,
            String identityProviderHint) {
        if (authorizationRequest == null || !"google".equals(identityProviderHint)) {
            return authorizationRequest;
        }

        Map<String, Object> additionalParameters =
                new LinkedHashMap<>(authorizationRequest.getAdditionalParameters());
        additionalParameters.put("kc_idp_hint", "google");
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(additionalParameters)
                .build();
    }

    private AuthenticationSuccessHandler loginSuccessHandler() {
        return (request, response, authentication) -> {
            String returnUrl = "/";
            boolean popup = false;
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object candidate = session.getAttribute(LoginReturnUrl.SESSION_ATTRIBUTE);
                Object popupCandidate = session.getAttribute(LoginReturnUrl.POPUP_SESSION_ATTRIBUTE);
                session.removeAttribute(LoginReturnUrl.SESSION_ATTRIBUTE);
                session.removeAttribute(LoginReturnUrl.POPUP_SESSION_ATTRIBUTE);
                popup = Boolean.TRUE.equals(popupCandidate);
                if (candidate instanceof String value) {
                    returnUrl = LoginReturnUrl.sanitize(value).orElse("/");
                }
            }
            String targetUrl = LoginReturnUrl.joinWithBaseUri(loginSuccessBaseUri, returnUrl);
            if (popup) {
                sendPopupCompletion(response, targetUrl, returnUrl);
                return;
            }
            response.sendRedirect(targetUrl);
        };
    }

    private void sendPopupCompletion(
            jakarta.servlet.http.HttpServletResponse response,
            String targetUrl,
            String returnUrl) throws IOException {
        response.setStatus(200);
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        String targetOrigin = originOf(loginSuccessBaseUri);
        response.getWriter().write("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>MSB Commerce sign-in complete</title>
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                </head>
                <body>
                  <p>Sign-in complete. You can close this window.</p>
                  <script>
                    (function () {
                      var message = { type: 'MSB_AUTH_COMPLETE', returnUrl: '%s' };
                      if (window.opener && !window.opener.closed) {
                        window.opener.postMessage(message, '%s');
                        window.close();
                      }
                      window.location.replace('%s');
                    })();
                  </script>
                </body>
                </html>
                """.formatted(
                jsString(returnUrl),
                jsString(targetOrigin),
                jsString(targetUrl)));
    }

    private String originOf(String uri) {
        URI parsed = URI.create(uri);
        String port = parsed.getPort() >= 0 ? ":" + parsed.getPort() : "";
        return parsed.getScheme() + "://" + parsed.getHost() + port;
    }

    private String jsString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", "");
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler(
            OAuth2AuthorizedClientService authorizedClientService) {
        return (request, response, authentication) -> {
            String targetUrl = oidcLogoutRedirectUri(
                    authentication,
                    logoutRedirectUri(request.getParameter("client"), authentication));
            removeAuthorizedClient(authorizedClientService, authentication);
            response.sendRedirect(targetUrl);
        };
    }

    private String oidcLogoutRedirectUri(Authentication authentication, String postLogoutRedirectUri) {
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                && oidcUser.getIdToken() != null) {
            return UriComponentsBuilder.fromUriString(oidcLogoutUri)
                    .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
                    .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
                    .build()
                    .encode()
                    .toUriString();
        }
        return postLogoutRedirectUri;
    }

    private String logoutRedirectUri(String requestedClient, Authentication authentication) {
        String client = requestedClient != null && SUPPORTED_LOGOUT_CLIENTS.contains(requestedClient)
                ? requestedClient
                : logoutClientFromAuthentication(authentication);
        return switch (client) {
            case "seller-portal" -> LoginReturnUrl.joinWithBaseUri(
                    loginSuccessBaseUri,
                    "/login?client=seller-portal&signedOut=1");
            case "admin-portal" -> LoginReturnUrl.joinWithBaseUri(
                    loginSuccessBaseUri,
                    "/login?client=admin-portal&signedOut=1");
            default -> marketplaceLogoutRedirectUri();
        };
    }

    private String logoutClientFromAuthentication(Authentication authentication) {
        OAuth2AuthenticationToken oauth2Authentication = oauth2Authentication(authentication);
        if (oauth2Authentication == null) {
            return "marketplace";
        }
        String registrationId = oauth2Authentication.getAuthorizedClientRegistrationId();
        return SUPPORTED_LOGOUT_CLIENTS.contains(registrationId) ? registrationId : "marketplace";
    }

    private String marketplaceLogoutRedirectUri() {
        return UriComponentsBuilder.fromUriString(logoutRedirectUri)
                .replaceQueryParam("signedOut", "1")
                .build()
                .toUriString();
    }

    // Removes server-side OAuth tokens so a logged-out browser must start a fresh login flow.
    private void removeAuthorizedClient(
            OAuth2AuthorizedClientService authorizedClientService,
            Authentication authentication) {
        OAuth2AuthenticationToken oauth2Authentication = oauth2Authentication(authentication);
        if (oauth2Authentication == null) {
            return;
        }
        authorizedClientService.removeAuthorizedClient(
                oauth2Authentication.getAuthorizedClientRegistrationId(),
                oauth2Authentication.getName());
    }

    private OAuth2AuthenticationToken oauth2Authentication(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauth2Authentication) {
            return oauth2Authentication;
        }
        return null;
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
