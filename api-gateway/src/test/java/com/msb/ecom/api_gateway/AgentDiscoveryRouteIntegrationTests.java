package com.msb.ecom.api_gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(AgentDiscoveryRouteIntegrationTests.DispatchAuditConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "msb.gateway.features.agent=false",
                "msb.gateway.features.agent-discovery=true",
                "spring.cloud.gateway.mvc.streaming-buffer-size=128",
                "resilience4j.timelimiter.instances.agentServiceCircuitBreaker.timeout-duration=100ms",
                "resilience4j.timelimiter.instances.agentDiscoveryServiceCircuitBreaker.timeout-duration=750ms"
        })
class AgentDiscoveryRouteIntegrationTests {

    private static final String ACCEPTED_EVENT = "event: activity\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2\","
            + "\"sequence\":1,\"type\":\"activity\",\"stage\":\"MESSAGE_ACCEPTED\","
            + "\"label\":\"Request accepted\"}\n\n";
    private static final String V2_STARTED_EVENT = "event: message_started\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1\","
            + "\"sequence\":1,\"type\":\"message_started\",\"userMessage\":{"
            + "\"id\":\"01A00000000000000000000002\",\"role\":\"USER\","
            + "\"body\":\"hello\",\"createdAt\":\"2026-07-30T00:00:00Z\"}}\n\n";
    private static final String V2_COMPLETE_STREAM_TAIL = "event: text_delta\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1\","
            + "\"sequence\":2,\"type\":\"text_delta\",\"delta\":\"Hello.\"}\n\n"
            + "event: attachments\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1\","
            + "\"sequence\":3,\"type\":\"attachments\",\"items\":[]}\n\n"
            + "event: done\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_AGENT_V2_STREAM_EVENT_V1\","
            + "\"sequence\":4,\"type\":\"done\",\"messageId\":\"01A00000000000000000000003\","
            + "\"response\":{\"sessionId\":\"01A00000000000000000000001\",\"userMessage\":{"
            + "\"id\":\"01A00000000000000000000002\",\"role\":\"USER\",\"body\":\"hello\","
            + "\"createdAt\":\"2026-07-30T00:00:00Z\"},\"assistantMessageId\":"
            + "\"01A00000000000000000000003\",\"message\":{\"role\":\"ASSISTANT\","
            + "\"content\":\"Hello.\",\"attachments\":[],\"citations\":[],\"toolActivity\":[],"
            + "\"inputTokens\":1,\"outputTokens\":1},\"decisionCount\":1}}\n\n";
    private static final String COMPLETE_STREAM_TAIL = "event: text_delta\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2\","
            + "\"sequence\":2,\"type\":\"text_delta\",\"delta\":\"No verified match.\"}\n\n"
            + "event: recommendations\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2\","
            + "\"sequence\":3,\"type\":\"recommendations\",\"items\":[]}\n\n"
            + "event: metadata\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2\","
            + "\"sequence\":4,\"type\":\"metadata\",\"citations\":[],\"provenance\":[]}\n\n"
            + "event: done\n"
            + "data: {\"schemaVersion\":\"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2\","
            + "\"sequence\":5,\"type\":\"done\",\"messageId\":\"01A00000000000000000000003\","
            + "\"response\":{\"userMessage\":{\"id\":\"01A00000000000000000000002\","
            + "\"role\":\"USER\",\"body\":\"lamp\",\"createdAt\":\"2026-07-29T00:00:00Z\"},"
            + "\"result\":{\"outcome\":\"NO_RESULTS\",\"message\":\"No verified match.\","
            + "\"questions\":[],\"recommendations\":[],\"preferenceState\":{\"query\":\"lamp\","
            + "\"categoryId\":null,\"condition\":null,\"minPrice\":null,\"maxPrice\":null,"
            + "\"city\":null,\"county\":null,\"selectedListingId\":null},\"inputTokens\":0,"
            + "\"outputTokens\":0,\"estimatedCost\":\"0\"},\"preferenceVersion\":0}}\n\n";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> ACTOR_USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> KEYCLOAK_SUB = new AtomicReference<>();
    private static final AtomicReference<String> ROLES = new AtomicReference<>();
    private static final AtomicReference<String> CORRELATION_ID = new AtomicReference<>();
    private static final AtomicReference<String> METHOD = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger STREAM_ASYNC_DISPATCHES = new AtomicInteger();
    private static final HttpServer AGENT_UPSTREAM = startAgentUpstream();

    @LocalServerPort
    private Integer port;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void agentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "service.agent.url",
                () -> "http://127.0.0.1:" + AGENT_UPSTREAM.getAddress().getPort());
    }

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        AUTHORIZATION.set(null);
        USER_ID.set(null);
        ACTOR_USER_ID.set(null);
        KEYCLOAK_SUB.set(null);
        ROLES.set(null);
        CORRELATION_ID.set(null);
        METHOD.set(null);
        REQUESTS.set(0);
        STREAM_ASYNC_DISPATCHES.set(0);
        when(jwtDecoder.decode(anyString())).thenReturn(
                Jwt.withTokenValue("relayed-access-token")
                        .header("alg", "none")
                        .claim("sub", "discovery-user-subject")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build());
    }

    @AfterAll
    static void stopAgentUpstream() {
        AGENT_UPSTREAM.stop(0);
    }

    @Test
    void authenticatedDiscoveryRouteRelaysBearerAndStripsSpoofedIdentity() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .header("X-Correlation-Id", "discovery-ui-correlation-1")
                .header("X-User-Id", "spoofed-user")
                .header("X-Actor-User-Id", "spoofed-actor")
                .header("X-Keycloak-Sub", "spoofed-subject")
                .header("X-Roles", "ADMIN")
                .when()
                .get("/api/v1/agent/discovery/sessions/01A00000000000000000000001")
                .then()
                .statusCode(200)
                .body("sessionType", equalTo("MARKETPLACE_DISCOVERY"));

        org.assertj.core.api.Assertions.assertThat(AUTHORIZATION.get())
                .isEqualTo("Bearer relayed-access-token");
        org.assertj.core.api.Assertions.assertThat(CORRELATION_ID.get())
                .isEqualTo("discovery-ui-correlation-1");
        org.assertj.core.api.Assertions.assertThat(USER_ID.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(ACTOR_USER_ID.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(KEYCLOAK_SUB.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(ROLES.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void discoveryPostRequiresCsrfBeforeCallingUpstream() throws Exception {
        mockMvc.perform(post("/api/v1/agent/discovery/sessions")
                        .with(oidcLogin())
                        .contentType("application/json")
                        .content("{\"sessionType\":\"MARKETPLACE_DISCOVERY\",\"newSearch\":false}"))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void authenticatedCsrfReadyDiscoveryPostReachesOnlyDiscoveryUpstream() {
        Response session = RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/auth/session");
        String csrfToken = session.jsonPath().getString("csrf.token");

        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .header("X-CSRF-TOKEN", csrfToken)
                .cookies(session.cookies())
                .contentType("application/json")
                .body("{\"sessionType\":\"MARKETPLACE_DISCOVERY\",\"newSearch\":false}")
                .when()
                .post("/api/v1/agent/discovery/sessions")
                .then()
                .statusCode(200)
                .body("sessionType", equalTo("MARKETPLACE_DISCOVERY"));

        org.assertj.core.api.Assertions.assertThat(METHOD.get()).isEqualTo("POST");
        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void discoveryRouteRejectsGuestsBeforeCallingUpstream() {
        RestAssured.given()
                .when()
                .get("/api/v1/agent/discovery/sessions/01A00000000000000000000001")
                .then()
                .statusCode(401);

        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void discoveryStreamRejectsGuestsBeforeCallingUpstream() {
        RestAssured.given()
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/v1/agent/discovery/sessions/01A00000000000000000000001/messages/stream")
                .then()
                .statusCode(401);

        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void discoveryCircuitBreakerMapsTransportFailureToSafeFallback() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/agent/discovery/failure")
                .then()
                .statusCode(503)
                .body("error.code", equalTo("SERVICE_UNAVAILABLE"));

        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isPositive();
    }

    @Test
    void discoveryRouteUsesDiscoveryTransportWindowInsteadOfListingAgentWindow() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/agent/discovery/slow")
                .then()
                .statusCode(200)
                .body("sessionType", equalTo("MARKETPLACE_DISCOVERY"));

        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void discoveryRouteTimesOutBeyondDiscoveryTransportWindow() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/agent/discovery/too-slow")
                .then()
                .statusCode(503)
                .body("error.code", equalTo("SERVICE_UNAVAILABLE"));

        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void discoveryRouteForwardsFirstStreamEventBeforeUpstreamClose() throws Exception {
        HttpServer frontProxy = startNginxLikeProxy(port);
        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + frontProxy.getAddress().getPort()
                            + "/api/v1/agent/discovery/stream"))
                    .header("Authorization", "Bearer relayed-access-token")
                    .GET()
                    .build();
            long started = System.nanoTime();

            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] expected = ACCEPTED_EVENT.getBytes(StandardCharsets.UTF_8);
            byte[] first = response.body().readNBytes(expected.length);
            long firstEventMillis = (System.nanoTime() - started) / 1_000_000;

            org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/event-stream");
            org.assertj.core.api.Assertions.assertThat(first).isEqualTo(expected);
            org.assertj.core.api.Assertions.assertThat(new String(first, StandardCharsets.UTF_8))
                    .contains("\"schemaVersion\":\"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2\"")
                    .contains("\"sequence\":1")
                    .contains("\"type\":\"activity\"");
            org.assertj.core.api.Assertions.assertThat(firstEventMillis).isLessThan(300);
            response.body().readAllBytes();
            response.body().close();
            org.assertj.core.api.Assertions.assertThat(AUTHORIZATION.get())
                    .isEqualTo("Bearer relayed-access-token");
        }
        finally {
            frontProxy.stop(0);
        }
    }

    @Test
    void discoveryStreamIsIncrementalAndStrictThroughRealNginx() throws Exception {
        Response session = RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/auth/session");
        String csrfToken = session.jsonPath().getString("csrf.token");
        String cookieHeader = session.cookies().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
        Testcontainers.exposeHostPorts(port);
        String nginxConfig = "server { listen 8080; location ^~ /api/v1/agent/discovery/ {"
                + " proxy_pass http://host.testcontainers.internal:" + port + ";"
                + " proxy_http_version 1.1; proxy_buffering off; proxy_cache off;"
                + " proxy_read_timeout 2s; } }";

        try (GenericContainer<?> nginx = new GenericContainer<>(DockerImageName.parse("nginx:alpine"))
                .withCopyToContainer(
                        Transferable.of(nginxConfig.getBytes(StandardCharsets.UTF_8), 0644),
                        "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(8080)) {
            nginx.start();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + nginx.getMappedPort(8080)
                            + "/api/v1/agent/discovery/sessions/01A00000000000000000000001/messages/stream"))
                    .header("Authorization", "Bearer relayed-access-token")
                    .header("X-CSRF-TOKEN", csrfToken)
                    .header("Cookie", cookieHeader)
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("X-User-Id", "spoofed-user")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            long started = System.nanoTime();
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] accepted = response.body().readNBytes(ACCEPTED_EVENT.getBytes(StandardCharsets.UTF_8).length);
            long firstEventMillis = (System.nanoTime() - started) / 1_000_000;
            byte[] remainder = response.body().readAllBytes();
            response.body().close();
            String complete = new String(accepted, StandardCharsets.UTF_8)
                    + new String(remainder, StandardCharsets.UTF_8);

            org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(
                    response.headers().firstValue("Content-Type").orElse(""))
                    .isEqualTo("text/event-stream");
            org.assertj.core.api.Assertions.assertThat(new String(accepted, StandardCharsets.UTF_8))
                    .isEqualTo(ACCEPTED_EVENT);
            org.assertj.core.api.Assertions.assertThat(firstEventMillis).isLessThan(500);
            assertStrictCompletedStream(complete);
            org.assertj.core.api.Assertions.assertThat(STREAM_ASYNC_DISPATCHES.get()).isZero();
            org.assertj.core.api.Assertions.assertThat(AUTHORIZATION.get())
                    .isEqualTo("Bearer relayed-access-token");
            org.assertj.core.api.Assertions.assertThat(USER_ID.get()).isNull();
        }
    }

    @Test
    void marketplaceV2StreamIsIncrementalThroughRealNginx() throws Exception {
        Response session = RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/auth/session");
        String cookieHeader = session.cookies().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
        Testcontainers.exposeHostPorts(port);
        String nginxConfig = "server { listen 8080; location ^~ /api/v1/agent/marketplace-v2/ {"
                + " proxy_pass http://host.testcontainers.internal:" + port + ";"
                + " proxy_http_version 1.1; proxy_buffering off; proxy_cache off;"
                + " proxy_read_timeout 2s; } }";

        try (GenericContainer<?> nginx = new GenericContainer<>(DockerImageName.parse("nginx:alpine"))
                .withCopyToContainer(
                        Transferable.of(nginxConfig.getBytes(StandardCharsets.UTF_8), 0644),
                        "/etc/nginx/conf.d/default.conf")
                .withExposedPorts(8080)) {
            nginx.start();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + nginx.getMappedPort(8080)
                            + "/api/v1/agent/marketplace-v2/sessions/01A00000000000000000000001/messages/stream"))
                    .header("Authorization", "Bearer relayed-access-token")
                    .header("X-CSRF-TOKEN", session.jsonPath().getString("csrf.token"))
                    .header("Cookie", cookieHeader)
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            long started = System.nanoTime();
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] first = response.body().readNBytes(V2_STARTED_EVENT.getBytes(StandardCharsets.UTF_8).length);
            long firstEventMillis = (System.nanoTime() - started) / 1_000_000;
            String stream = new String(first, StandardCharsets.UTF_8)
                    + new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            response.body().close();

            org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .isEqualTo("text/event-stream");
            org.assertj.core.api.Assertions.assertThat(new String(first, StandardCharsets.UTF_8))
                    .isEqualTo(V2_STARTED_EVENT);
            org.assertj.core.api.Assertions.assertThat(firstEventMillis).isLessThan(500);
            org.assertj.core.api.Assertions.assertThat(stream)
                    .isEqualTo(V2_STARTED_EVENT + V2_COMPLETE_STREAM_TAIL);
        }
    }

    @Test
    void discoveryStreamTimeoutClosesStartedSseWithoutAppendingJson() throws Exception {
        Response session = RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/auth/session");
        String cookieHeader = session.cookies().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/agent/discovery/sessions/01A00000000000000000000001/messages/"
                        + "01A00000000000000000000002/response-retry/stream"))
                .header("Authorization", "Bearer relayed-access-token")
                .header("X-CSRF-TOKEN", session.jsonPath().getString("csrf.token"))
                .header("Cookie", cookieHeader)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofByteArray());

        org.assertj.core.api.Assertions.assertThat(response.statusCode()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(ACCEPTED_EVENT)
                .doesNotContain("SERVICE_UNAVAILABLE")
                .doesNotContain("application/json");
    }

    private static void assertStrictCompletedStream(String stream) throws Exception {
        org.assertj.core.api.Assertions.assertThat(stream).isEqualTo(ACCEPTED_EVENT + COMPLETE_STREAM_TAIL);
        String[] frames = stream.split("\\n\\n");
        List<String> types = new ArrayList<>();
        for (int index = 0; index < frames.length; index++) {
            String[] lines = frames[index].split("\\n", -1);
            org.assertj.core.api.Assertions.assertThat(lines).hasSize(2);
            org.assertj.core.api.Assertions.assertThat(lines[0]).startsWith("event: ");
            org.assertj.core.api.Assertions.assertThat(lines[1]).startsWith("data: ");
            String type = lines[0].substring("event: ".length());
            JsonNode event = OBJECT_MAPPER.readTree(lines[1].substring("data: ".length()));
            org.assertj.core.api.Assertions.assertThat(event.get("schemaVersion").asText())
                    .isEqualTo("MARKETPLACE_DISCOVERY_STREAM_EVENT_V2");
            org.assertj.core.api.Assertions.assertThat(event.get("sequence").asInt()).isEqualTo(index + 1);
            org.assertj.core.api.Assertions.assertThat(event.get("type").asText()).isEqualTo(type);
            types.add(type);
        }
        org.assertj.core.api.Assertions.assertThat(types)
                .containsExactly("activity", "text_delta", "recommendations", "metadata", "done");
    }

    private static HttpServer startNginxLikeProxy(int gatewayPort) throws IOException {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + gatewayPort + exchange.getRequestURI()))
                    .GET();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            try {
                HttpResponse<InputStream> upstream = HttpClient.newHttpClient().send(
                        request.build(), HttpResponse.BodyHandlers.ofInputStream());
                upstream.headers().firstValue("Content-Type")
                        .ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
                exchange.sendResponseHeaders(upstream.statusCode(), 0);
                byte[] buffer = new byte[64];
                int read;
                while ((read = upstream.body().read(buffer)) != -1) {
                    exchange.getResponseBody().write(buffer, 0, read);
                    exchange.getResponseBody().flush();
                }
                exchange.close();
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        proxy.start();
        return proxy;
    }

    private static HttpServer startAgentUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/agent/discovery/", AgentDiscoveryRouteIntegrationTests::respond);
            server.createContext("/api/v1/agent/marketplace-v2/", AgentDiscoveryRouteIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("Could not start Agent discovery route test upstream.", error);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        if (exchange.getRequestURI().getPath().startsWith("/api/v1/agent/marketplace-v2/")
                && exchange.getRequestURI().getPath().endsWith("/messages/stream")) {
            AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(V2_STARTED_EVENT.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleepForRouteTest(400);
            exchange.getResponseBody().write(V2_COMPLETE_STREAM_TAIL.getBytes(StandardCharsets.UTF_8));
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/response-retry/stream")) {
            AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(ACCEPTED_EVENT.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleepForRouteTest(1_000);
            try {
                exchange.getResponseBody().write(COMPLETE_STREAM_TAIL.getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException ignored) {
                // The bounded Gateway deadline intentionally closes this fixture.
            }
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/messages/stream")) {
            METHOD.set(exchange.getRequestMethod());
            AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            USER_ID.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            ACTOR_USER_ID.set(exchange.getRequestHeaders().getFirst("X-Actor-User-Id"));
            KEYCLOAK_SUB.set(exchange.getRequestHeaders().getFirst("X-Keycloak-Sub"));
            ROLES.set(exchange.getRequestHeaders().getFirst("X-Roles"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(ACCEPTED_EVENT.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleepForRouteTest(400);
            exchange.getResponseBody().write(COMPLETE_STREAM_TAIL.getBytes(StandardCharsets.UTF_8));
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/stream")) {
            METHOD.set(exchange.getRequestMethod());
            AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("event: activity\n"
                    + "data: {\"schemaVersion\":\"MARKETPLACE_DISCOVERY_STREAM_EVENT_V2\","
                    + "\"sequence\":1,\"type\":\"activity\",\"stage\":\"MESSAGE_ACCEPTED\","
                    + "\"label\":\"Request accepted\"}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            sleepForRouteTest(400);
            exchange.getResponseBody().write(
                    "event: completed\ndata: last\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/failure")) {
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/slow")) {
            sleepForRouteTest(250);
        }
        if (exchange.getRequestURI().getPath().endsWith("/too-slow")) {
            sleepForRouteTest(1_000);
        }
        METHOD.set(exchange.getRequestMethod());
        AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
        USER_ID.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
        ACTOR_USER_ID.set(exchange.getRequestHeaders().getFirst("X-Actor-User-Id"));
        KEYCLOAK_SUB.set(exchange.getRequestHeaders().getFirst("X-Keycloak-Sub"));
        ROLES.set(exchange.getRequestHeaders().getFirst("X-Roles"));
        CORRELATION_ID.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        byte[] body = ("{\"id\":\"01A00000000000000000000001\","
                + "\"sessionType\":\"MARKETPLACE_DISCOVERY\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void sleepForRouteTest(long milliseconds) throws IOException {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while exercising discovery timeout contract.", interrupted);
        }
    }

    @TestConfiguration
    static class DispatchAuditConfiguration {

        @Bean
        FilterRegistrationBean<Filter> discoveryStreamDispatchAudit() {
            Filter filter = (request, response, chain) -> {
                if (request.getDispatcherType() == DispatcherType.ASYNC
                        && request instanceof jakarta.servlet.http.HttpServletRequest httpRequest
                        && httpRequest.getRequestURI().endsWith("/messages/stream")) {
                    STREAM_ASYNC_DISPATCHES.incrementAndGet();
                }
                chain.doFilter(request, response);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
            registration.setOrder(Integer.MIN_VALUE);
            return registration;
        }
    }
}
