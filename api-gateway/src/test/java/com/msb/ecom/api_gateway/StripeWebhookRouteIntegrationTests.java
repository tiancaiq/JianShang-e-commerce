package com.msb.ecom.api_gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "msb.gateway.features.stripe-webhook=true")
class StripeWebhookRouteIntegrationTests {

    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicReference<String> SIGNATURE = new AtomicReference<>();
    private static final HttpServer PAYMENT_UPSTREAM = startUpstream();

    @LocalServerPort
    private Integer port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void paymentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("service.payment.url",
                () -> "http://127.0.0.1:" + PAYMENT_UPSTREAM.getAddress().getPort());
    }

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        BODY.set(null);
        SIGNATURE.set(null);
    }

    @AfterAll
    static void stopUpstream() {
        PAYMENT_UPSTREAM.stop(0);
    }

    @Test
    void exactStripeWebhookRouteAcceptsUnsignedBrowserSessionAndPreservesSignedBody() {
        String payload = "{\"id\":\"evt_gateway_test\"}";

        RestAssured.given()
                .header("Stripe-Signature", "t=1700000000,v1=signature")
                .contentType("application/json")
                .body(payload)
                .when()
                .post("/api/v1/webhooks/payments/STRIPE_TEST_V1")
                .then()
                .statusCode(200);

        assertThat(BODY.get()).isEqualTo(payload);
        assertThat(SIGNATURE.get()).isEqualTo("t=1700000000,v1=signature");
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/v1/webhooks/payments/STRIPE_TEST_V1",
                    StripeWebhookRouteIntegrationTests::respond);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start Stripe webhook test upstream.", exception);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        SIGNATURE.set(exchange.getRequestHeaders().getFirst("Stripe-Signature"));
        byte[] response = "{\"outcome\":\"APPLIED\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
