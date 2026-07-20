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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "msb.gateway.features.agent=false",
                "msb.gateway.features.agent-discovery=true"
        })
class AgentDiscoveryRouteIntegrationTests {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> ACTOR_USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> KEYCLOAK_SUB = new AtomicReference<>();
    private static final AtomicReference<String> ROLES = new AtomicReference<>();
    private static final AtomicReference<String> CORRELATION_ID = new AtomicReference<>();
    private static final AtomicReference<String> METHOD = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
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

    private static HttpServer startAgentUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/agent/discovery/", AgentDiscoveryRouteIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("Could not start Agent discovery route test upstream.", error);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        if (exchange.getRequestURI().getPath().endsWith("/failure")) {
            exchange.close();
            return;
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
}
