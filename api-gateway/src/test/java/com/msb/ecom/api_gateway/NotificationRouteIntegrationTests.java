package com.msb.ecom.api_gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
        properties = "msb.gateway.features.notifications=true")
class NotificationRouteIntegrationTests {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> ACTOR_USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> KEYCLOAK_SUB = new AtomicReference<>();
    private static final AtomicReference<String> ROLES = new AtomicReference<>();
    private static final AtomicReference<String> CORRELATION_ID = new AtomicReference<>();
    private static final AtomicReference<String> METHOD = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer NOTIFICATION_UPSTREAM = startNotificationUpstream();

    @LocalServerPort
    private Integer port;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void notificationServiceUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "service.notification.url",
                () -> "http://127.0.0.1:" + NOTIFICATION_UPSTREAM.getAddress().getPort());
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
                        .claim("sub", "notification-user-subject")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build());
    }

    @AfterAll
    static void stopNotificationUpstream() {
        NOTIFICATION_UPSTREAM.stop(0);
    }

    @Test
    void authenticatedNotificationRouteRelaysBearerAndStripsSpoofedIdentity() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .header("X-Correlation-Id", "notification-ui-correlation-1")
                .header("X-User-Id", "spoofed-user")
                .header("X-Actor-User-Id", "spoofed-actor")
                .header("X-Keycloak-Sub", "spoofed-subject")
                .header("X-Roles", "ADMIN")
                .when()
                .get("/api/v1/notifications")
                .then()
                .statusCode(200)
                .body("page.hasMore", equalTo(false));

        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer relayed-access-token");
        assertThat(CORRELATION_ID.get()).isEqualTo("notification-ui-correlation-1");
        assertThat(USER_ID.get()).isNull();
        assertThat(ACTOR_USER_ID.get()).isNull();
        assertThat(KEYCLOAK_SUB.get()).isNull();
        assertThat(ROLES.get()).isNull();
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void authenticatedBusinessNotificationRouteUsesTheSameProtectedBoundary() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .header("X-User-Id", "spoofed-user")
                .when()
                .get("/api/v1/businesses/01K0BUSINESS00000000000000/notifications/unread-count")
                .then()
                .statusCode(200)
                .body("unreadCount", equalTo(0));

        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer relayed-access-token");
        assertThat(USER_ID.get()).isNull();
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void notificationPostRequiresCsrfBeforeCallingUpstream() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read-all")
                        .with(oidcLogin()))
                .andExpect(status().isForbidden());

        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void authenticatedCsrfReadyNotificationPostReachesNotificationUpstream() {
        Response session = RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/auth/session");
        String csrfToken = session.jsonPath().getString("csrf.token");

        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .header("X-CSRF-TOKEN", csrfToken)
                .cookies(session.cookies())
                .when()
                .post("/api/v1/notifications/read-all")
                .then()
                .statusCode(204);

        assertThat(METHOD.get()).isEqualTo("POST");
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void notificationRouteRejectsGuestsBeforeCallingUpstream() {
        RestAssured.given()
                .when()
                .get("/api/v1/notifications")
                .then()
                .statusCode(401);

        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void notificationCircuitBreakerMapsTransportFailureToSafeFallback() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .when()
                .get("/api/v1/notifications/failure")
                .then()
                .statusCode(503)
                .body("error.code", equalTo("SERVICE_UNAVAILABLE"));

        assertThat(REQUESTS.get()).isPositive();
    }

    private static HttpServer startNotificationUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/notifications", NotificationRouteIntegrationTests::respond);
            server.createContext("/api/v1/businesses", NotificationRouteIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("Could not start notification route test upstream.", error);
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
        if ("POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        byte[] body = (exchange.getRequestURI().getPath().endsWith("/unread-count")
                ? "{\"unreadCount\":0}"
                : "{\"items\":[],\"page\":{\"nextCursor\":null,\"hasMore\":false}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
