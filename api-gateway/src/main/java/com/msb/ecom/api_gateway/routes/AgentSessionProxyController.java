package com.msb.ecom.api_gateway.routes;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@RestController
@ConditionalOnProperty(prefix = "msb.gateway.features", name = "agent", havingValue = "true")
class AgentSessionProxyController {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final String agentServiceUrl;

    AgentSessionProxyController(
            OAuth2AuthorizedClientService authorizedClientService,
            @Value("${service.agent.url}") String agentServiceUrl) {
        this.authorizedClientService = authorizedClientService;
        this.agentServiceUrl = agentServiceUrl.endsWith("/")
                ? agentServiceUrl.substring(0, agentServiceUrl.length() - 1)
                : agentServiceUrl;
    }

    @PostMapping("/api/v1/agent/sessions")
    ResponseEntity<String> createSession(
            @RequestBody String body,
            HttpServletRequest request,
            Authentication authentication) {
        return relay(HttpMethod.POST, "/api/v1/agent/sessions", body, request, authentication);
    }

    @GetMapping("/api/v1/agent/sessions/{sessionId}")
    ResponseEntity<String> getSession(
            @PathVariable String sessionId,
            HttpServletRequest request,
            Authentication authentication) {
        return relay(HttpMethod.GET, "/api/v1/agent/sessions/" + sessionId, null, request, authentication);
    }

    @GetMapping("/api/v1/agent/sessions/{sessionId}/messages")
    ResponseEntity<String> getMessages(
            @PathVariable String sessionId,
            HttpServletRequest request,
            Authentication authentication) {
        return relay(HttpMethod.GET, "/api/v1/agent/sessions/" + sessionId + "/messages", null, request, authentication);
    }

    @PostMapping("/api/v1/agent/sessions/{sessionId}/messages")
    ResponseEntity<String> sendMessage(
            @PathVariable String sessionId,
            @RequestBody String body,
            HttpServletRequest request,
            Authentication authentication) {
        return relay(HttpMethod.POST, "/api/v1/agent/sessions/" + sessionId + "/messages", body, request, authentication);
    }

    // Relays only BFF-authenticated Agent session calls; browser-supplied actor headers are never forwarded.
    private ResponseEntity<String> relay(
            HttpMethod method,
            String path,
            String body,
            HttpServletRequest request,
            Authentication authentication) {
        OAuth2AuthorizedClient authorizedClient = authorizedClient(authentication);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            HttpURLConnection connection = (HttpURLConnection) URI
                    .create(agentServiceUrl + path + queryString(request))
                    .toURL()
                    .openConnection();
            connection.setRequestMethod(method.name());
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION,
                    "Bearer " + authorizedClient.getAccessToken().getTokenValue());
            connection.setRequestProperty(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            String correlationId = request.getHeader("X-Correlation-Id");
            if (correlationId != null && !correlationId.isBlank()) {
                connection.setRequestProperty("X-Correlation-Id", correlationId);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                connection.setRequestProperty(HttpHeaders.CONTENT_LENGTH, String.valueOf(bytes.length));
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            HttpHeaders responseHeaders = new HttpHeaders();
            String contentType = connection.getHeaderField(HttpHeaders.CONTENT_TYPE);
            if (contentType != null && !contentType.isBlank()) {
                responseHeaders.set(HttpHeaders.CONTENT_TYPE, contentType);
            }
            String upstreamCorrelationId = connection.getHeaderField("X-Correlation-Id");
            if (upstreamCorrelationId != null && !upstreamCorrelationId.isBlank()) {
                responseHeaders.set("X-Correlation-Id", upstreamCorrelationId);
            }
            return new ResponseEntity<>(
                    responseBody(status >= 400 ? connection.getErrorStream() : connection.getInputStream()),
                    responseHeaders,
                    HttpStatus.valueOf(status));
        } catch (IOException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private OAuth2AuthorizedClient authorizedClient(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)) {
            return null;
        }
        return authorizedClientService.loadAuthorizedClient(
                oauth2Authentication.getAuthorizedClientRegistrationId(),
                oauth2Authentication.getName());
    }

    private String queryString(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || query.isBlank() ? "" : "?" + query;
    }

    private String responseBody(InputStream body) throws IOException {
        if (body == null) {
            return "";
        }
        return StreamUtils.copyToString(body, StandardCharsets.UTF_8);
    }
}
