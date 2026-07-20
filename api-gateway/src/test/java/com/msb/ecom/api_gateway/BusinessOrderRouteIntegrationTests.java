package com.msb.ecom.api_gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "msb.gateway.features.business-orders=true")
class BusinessOrderRouteIntegrationTests {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> ACTOR_USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> KEYCLOAK_SUB = new AtomicReference<>();
    private static final AtomicReference<String> ROLES = new AtomicReference<>();
    private static final AtomicReference<String> CORRELATION_ID = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer ORDER_UPSTREAM = startOrderUpstream();

    @LocalServerPort
    private Integer port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void orderServiceUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "service.order.url",
                () -> "http://127.0.0.1:" + ORDER_UPSTREAM.getAddress().getPort());
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
        REQUESTS.set(0);
        when(jwtDecoder.decode(anyString())).thenReturn(
                Jwt.withTokenValue("relayed-access-token")
                        .header("alg", "none")
                        .claim("sub", "business-user-subject")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build());
    }

    @AfterAll
    static void stopOrderUpstream() {
        ORDER_UPSTREAM.stop(0);
    }

    @Test
    void authenticatedBusinessOrderRouteRelaysTokenAndStripsBrowserIdentityHeaders() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-access-token")
                .header("X-Correlation-Id", "business-order-ui-1")
                .header("X-User-Id", "spoofed-user")
                .header("X-Actor-User-Id", "spoofed-actor")
                .header("X-Keycloak-Sub", "spoofed-subject")
                .header("X-Roles", "ADMIN")
                .when()
                .get("/api/v1/businesses/01B00000000000000000000001/orders")
                .then()
                .statusCode(200)
                .body("page.hasMore", equalTo(false));

        org.assertj.core.api.Assertions.assertThat(AUTHORIZATION.get())
                .isEqualTo("Bearer relayed-access-token");
        org.assertj.core.api.Assertions.assertThat(CORRELATION_ID.get())
                .isEqualTo("business-order-ui-1");
        org.assertj.core.api.Assertions.assertThat(USER_ID.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(ACTOR_USER_ID.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(KEYCLOAK_SUB.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(ROLES.get()).isNull();
        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void businessOrderRouteRejectsGuestsBeforeCallingUpstream() {
        RestAssured.given()
                .when()
                .get("/api/v1/businesses/01B00000000000000000000001/orders")
                .then()
                .statusCode(401);

        org.assertj.core.api.Assertions.assertThat(REQUESTS.get()).isZero();
    }

    private static HttpServer startOrderUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/businesses/", BusinessOrderRouteIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("Could not start business-order route test upstream.", error);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
        USER_ID.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
        ACTOR_USER_ID.set(exchange.getRequestHeaders().getFirst("X-Actor-User-Id"));
        KEYCLOAK_SUB.set(exchange.getRequestHeaders().getFirst("X-Keycloak-Sub"));
        ROLES.set(exchange.getRequestHeaders().getFirst("X-Roles"));
        CORRELATION_ID.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        byte[] body = "{\"items\":[],\"page\":{\"nextCursor\":null,\"hasMore\":false}}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
