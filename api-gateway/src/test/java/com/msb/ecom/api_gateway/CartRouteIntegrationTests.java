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
        properties = {
                "msb.gateway.features.cart=true",
                "msb.gateway.features.checkout=false"
        })
class CartRouteIntegrationTests {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> ACTOR_USER_ID = new AtomicReference<>();
    private static final AtomicReference<String> KEYCLOAK_SUB = new AtomicReference<>();
    private static final AtomicReference<String> ROLES = new AtomicReference<>();
    private static final AtomicReference<String> CORRELATION_ID = new AtomicReference<>();
    private static final AtomicReference<String> METHOD = new AtomicReference<>();
    private static final AtomicReference<String> REQUEST_BODY = new AtomicReference<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer ORDER_UPSTREAM = startOrderUpstream();

    @LocalServerPort
    private Integer port;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void orderServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("service.order.url", () -> "http://127.0.0.1:" + ORDER_UPSTREAM.getAddress().getPort());
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
        REQUEST_BODY.set(null);
        REQUESTS.set(0);
        when(jwtDecoder.decode(anyString())).thenReturn(
                Jwt.withTokenValue("relayed-cart-token")
                        .header("alg", "none")
                        .claim("sub", "cart-user-subject")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build());
    }

    @AfterAll
    static void stopOrderUpstream() {
        ORDER_UPSTREAM.stop(0);
    }

    @Test
    void authenticatedCartRouteRelaysBearerAndStripsSpoofedIdentity() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-cart-token")
                .header("X-Correlation-Id", "cart-ui-correlation-1")
                .header("X-User-Id", "spoofed-user")
                .header("X-Actor-User-Id", "spoofed-actor")
                .header("X-Keycloak-Sub", "spoofed-subject")
                .header("X-Roles", "ADMIN")
                .when()
                .get("/api/v1/cart")
                .then()
                .statusCode(200)
                .body("totalQuantity", equalTo(0));

        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer relayed-cart-token");
        assertThat(CORRELATION_ID.get()).isEqualTo("cart-ui-correlation-1");
        assertThat(USER_ID.get()).isNull();
        assertThat(ACTOR_USER_ID.get()).isNull();
        assertThat(KEYCLOAK_SUB.get()).isNull();
        assertThat(ROLES.get()).isNull();
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void cartPostRequiresCsrfBeforeCallingUpstream() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .with(oidcLogin())
                        .contentType("application/json")
                        .content("{\"listingId\":\"01L00000000000000000000001\",\"quantity\":1}"))
                .andExpect(status().isForbidden());

        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void authenticatedCsrfReadyCartPostReachesOrderUpstream() {
        Response session = RestAssured.given()
                .header("Authorization", "Bearer relayed-cart-token")
                .when()
                .get("/api/v1/auth/session");
        String csrfToken = session.jsonPath().getString("csrf.token");

        RestAssured.given()
                .header("Authorization", "Bearer relayed-cart-token")
                .header("X-CSRF-TOKEN", csrfToken)
                .cookies(session.cookies())
                .contentType("application/json")
                .body("{\"listingId\":\"01L00000000000000000000001\",\"quantity\":1}")
                .when()
                .post("/api/v1/cart/items")
                .then()
                .statusCode(200);

        assertThat(METHOD.get()).isEqualTo("POST");
        assertThat(REQUEST_BODY.get())
                .isEqualTo("{\"listingId\":\"01L00000000000000000000001\",\"quantity\":1}");
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void authenticatedCsrfReadyCartPatchRelaysJsonBody() {
        Response session = RestAssured.given()
                .header("Authorization", "Bearer relayed-cart-token")
                .when()
                .get("/api/v1/auth/session");
        String csrfToken = session.jsonPath().getString("csrf.token");

        RestAssured.given()
                .header("Authorization", "Bearer relayed-cart-token")
                .header("X-CSRF-TOKEN", csrfToken)
                .cookies(session.cookies())
                .contentType("application/json")
                .body("{\"quantity\":2}")
                .when()
                .patch("/api/v1/cart/items/01L00000000000000000000001")
                .then()
                .statusCode(200);

        assertThat(METHOD.get()).isEqualTo("PATCH");
        assertThat(REQUEST_BODY.get()).isEqualTo("{\"quantity\":2}");
        assertThat(REQUESTS.get()).isEqualTo(1);
    }

    @Test
    void checkoutNamespaceRemainsDisabledWhenOnlyCartIsEnabled() {
        RestAssured.given()
                .header("Authorization", "Bearer relayed-cart-token")
                .when()
                .get("/api/v1/checkouts/01C00000000000000000000001")
                .then()
                .statusCode(404);

        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void cartRouteRejectsGuestsBeforeCallingUpstream() {
        RestAssured.given()
                .when()
                .get("/api/v1/cart")
                .then()
                .statusCode(401);

        assertThat(REQUESTS.get()).isZero();
    }

    private static HttpServer startOrderUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/cart", CartRouteIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("Could not start cart route test upstream.", error);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        REQUESTS.incrementAndGet();
        METHOD.set(exchange.getRequestMethod());
        AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
        USER_ID.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
        ACTOR_USER_ID.set(exchange.getRequestHeaders().getFirst("X-Actor-User-Id"));
        KEYCLOAK_SUB.set(exchange.getRequestHeaders().getFirst("X-Keycloak-Sub"));
        ROLES.set(exchange.getRequestHeaders().getFirst("X-Roles"));
        CORRELATION_ID.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] body = "{\"version\":0,\"expiresAt\":null,\"itemCount\":0,\"totalQuantity\":0,\"totals\":[],\"items\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
