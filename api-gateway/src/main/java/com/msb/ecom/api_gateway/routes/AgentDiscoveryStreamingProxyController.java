package com.msb.ecom.api_gateway.routes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Relays only allowlisted Agent SSE commands through an explicitly flushed servlet stream. */
@RestController
@ConditionalOnProperty(prefix = "msb.gateway.features", name = "agent-discovery", havingValue = "true")
public class AgentDiscoveryStreamingProxyController {

    private static final Logger log = LoggerFactory.getLogger(AgentDiscoveryStreamingProxyController.class);
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;
    private static final int STREAM_COPY_BYTES = 128;
    private static final String SSE_MEDIA_TYPE = "text/event-stream";

    private final URI agentServiceUri;
    private final Duration timeout;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public AgentDiscoveryStreamingProxyController(
            @Value("${service.agent.url}") URI agentServiceUri,
            @Value("${resilience4j.timelimiter.instances.agentDiscoveryServiceCircuitBreaker.timeout-duration:35s}")
            Duration timeout,
            OAuth2AuthorizedClientManager authorizedClientManager) {
        this.agentServiceUri = agentServiceUri;
        this.timeout = timeout;
        this.authorizedClientManager = authorizedClientManager;
    }

    /** Opens one bounded upstream response and preserves its SSE bytes without Gateway MVC buffering. */
    @PostMapping(
            path = {
                    "/api/v1/agent/discovery/sessions/{sessionId}/messages/stream",
                    "/api/v1/agent/discovery/sessions/{sessionId}/messages/{userMessageId}/response-retry/stream",
                    "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/stream",
                    "/api/v1/agent/marketplace-v2/sessions/{sessionId}/messages/{userMessageId}/response-retry/stream"
            },
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public void stream(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication,
            @RequestBody byte[] requestBody) throws IOException {
        if (requestBody.length == 0 || requestBody.length > MAX_REQUEST_BYTES) {
            writeSafeFailure(response, HttpStatus.PAYLOAD_TOO_LARGE);
            return;
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        HttpURLConnection connection = openConnection(request, authentication, requestBody, deadlineNanos);
        int status;
        try {
            status = connection.getResponseCode();
        }
        catch (IOException error) {
            connection.disconnect();
            log.warn("event=AGENT_DISCOVERY_STREAM_RELAY_FAILURE phase=UPSTREAM_HEADERS category=IO");
            writeSafeFailure(response, HttpStatus.SERVICE_UNAVAILABLE);
            return;
        }

        String contentType = connection.getHeaderField(HttpHeaders.CONTENT_TYPE);
        if (status != HttpStatus.OK.value()) {
            byte[] errorBody = readBoundedError(connection, deadlineNanos);
            connection.disconnect();
            writeResponse(response, status, publicContentType(contentType).toString(), errorBody);
            return;
        }
        if (!isEventStream(contentType)) {
            connection.disconnect();
            log.warn("event=AGENT_DISCOVERY_STREAM_RELAY_FAILURE phase=UPSTREAM_HEADERS category=CONTENT_TYPE");
            writeSafeFailure(response, HttpStatus.BAD_GATEWAY);
            return;
        }

        response.setStatus(status);
        response.setContentType(SSE_MEDIA_TYPE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        String correlationId = connection.getHeaderField("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank() && correlationId.length() <= 100) {
            response.setHeader("X-Correlation-Id", correlationId);
        }
        response.setBufferSize(1);
        byte[] buffer = new byte[STREAM_COPY_BYTES];
        try (InputStream upstream = connection.getInputStream()) {
            while (true) {
                int remainingMillis = remainingMillis(deadlineNanos);
                if (remainingMillis <= 0) {
                    log.warn("event=AGENT_DISCOVERY_STREAM_RELAY_FAILURE phase=BODY category=DEADLINE");
                    return;
                }
                connection.setReadTimeout(remainingMillis);
                int read = upstream.read(buffer);
                if (read == -1) {
                    return;
                }
                response.getOutputStream().write(buffer, 0, read);
                response.flushBuffer();
            }
        }
        catch (SocketTimeoutException ignored) {
            log.warn("event=AGENT_DISCOVERY_STREAM_RELAY_FAILURE phase=BODY category=DEADLINE");
            // A started SSE response is closed; JSON must never be appended to it.
        }
        catch (IOException ignored) {
            log.info("event=AGENT_DISCOVERY_STREAM_RELAY_FAILURE phase=BODY category=DISCONNECT");
            // Browser cancellation and upstream disconnect both terminate byte relay.
        }
        finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(
            HttpServletRequest request,
            Authentication authentication,
            byte[] requestBody,
            long deadlineNanos) throws IOException {
        URI target = UriComponentsBuilder.fromUri(agentServiceUri)
                .path(request.getRequestURI())
                .build(true)
                .toUri();
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        int remainingMillis = Math.max(1, remainingMillis(deadlineNanos));
        connection.setConnectTimeout(remainingMillis);
        connection.setReadTimeout(remainingMillis);
        connection.setFixedLengthStreamingMode(requestBody.length);
        connection.setRequestProperty(HttpHeaders.ACCEPT, SSE_MEDIA_TYPE);
        connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        connection.setRequestProperty(HttpHeaders.AUTHORIZATION, bearerAuthorization(request, authentication));
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank() && correlationId.length() <= 100) {
            connection.setRequestProperty("X-Correlation-Id", correlationId);
        }
        connection.getOutputStream().write(requestBody);
        return connection;
    }

    private String bearerAuthorization(HttpServletRequest request, Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauth) {
            OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                    .withClientRegistrationId(oauth.getAuthorizedClientRegistrationId())
                    .principal(authentication)
                    .build();
            OAuth2AuthorizedClient client = authorizedClientManager.authorize(authorizeRequest);
            if (client == null || client.getAccessToken() == null) {
                throw new IllegalStateException("Authenticated client token is unavailable");
            }
            return "Bearer " + client.getAccessToken().getTokenValue();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalStateException("Authenticated bearer token is unavailable");
        }
        return authorization;
    }

    private static int remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, remainingNanos / 1_000_000));
    }

    private static boolean isEventStream(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.TEXT_EVENT_STREAM.isCompatibleWith(MediaType.parseMediaType(contentType));
        }
        catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static MediaType publicContentType(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_JSON : MediaType.parseMediaType(contentType);
        }
        catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_JSON;
        }
    }

    private static byte[] readBoundedError(HttpURLConnection connection, long deadlineNanos) throws IOException {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            return new byte[0];
        }
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1_024];
            while (output.size() <= MAX_ERROR_BYTES) {
                int remaining = remainingMillis(deadlineNanos);
                if (remaining <= 0) {
                    break;
                }
                connection.setReadTimeout(remaining);
                int read = input.read(buffer);
                if (read == -1) {
                    break;
                }
                output.write(buffer, 0, Math.min(read, MAX_ERROR_BYTES - output.size()));
                if (output.size() == MAX_ERROR_BYTES) {
                    break;
                }
            }
            return output.toByteArray();
        }
    }

    private static void writeSafeFailure(HttpServletResponse response, HttpStatus status) throws IOException {
        byte[] body = ("{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\","
                + "\"message\":\"Service temporarily unavailable\"}}")
                .getBytes(StandardCharsets.UTF_8);
        writeResponse(response, status.value(), MediaType.APPLICATION_JSON_VALUE, body);
    }

    private static void writeResponse(
            HttpServletResponse response,
            int status,
            String contentType,
            byte[] value) throws IOException {
        response.setStatus(status);
        response.setContentType(contentType);
        response.setContentLength(value.length);
        response.getOutputStream().write(value);
        response.flushBuffer();
    }
}
