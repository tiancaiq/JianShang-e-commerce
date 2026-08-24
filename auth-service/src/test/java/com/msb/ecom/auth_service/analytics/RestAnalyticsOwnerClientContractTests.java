package com.msb.ecom.auth_service.analytics;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.Granularity;
import static org.assertj.core.api.Assertions.assertThat;

class RestAnalyticsOwnerClientContractTests {

    @Test
    void productTrendRoundTripsTheTypedJsonAndInternalHeaders() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> correlation = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/admin/analytics/trends/listings-created", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    {"metric":"LISTINGS_CREATED","range":{"from":"2026-08-01T00:00:00Z",
                    "to":"2026-08-02T00:00:00Z","timezone":"UTC","previousFrom":null,
                    "previousTo":null},"granularity":"DAY","points":[{"bucketStart":
                    "2026-08-01T00:00:00Z","bucketEnd":"2026-08-02T00:00:00Z","value":3}],
                    "total":3,"generatedAt":"2026-08-02T00:00:01Z"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestAnalyticsOwnerClient client = new RestAnalyticsOwnerClient(RestClient.builder(),
                    baseUrl, baseUrl, baseUrl, "internal-test-token");

            var result = client.productListingTrend(
                    Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2026-08-02T00:00:00Z"),
                    Granularity.DAY, "correlation-test");

            assertThat(result.available()).isTrue();
            assertThat(result.data().metric()).isEqualTo("LISTINGS_CREATED");
            assertThat(result.data().points()).singleElement()
                    .satisfies(point -> {
                        assertThat(point.bucketStart())
                                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
                        assertThat(point.bucketEnd())
                                .isEqualTo(Instant.parse("2026-08-02T00:00:00Z"));
                        assertThat(point.value()).isEqualTo(3);
                    });
            assertThat(token).hasValue("internal-test-token");
            assertThat(correlation).hasValue("correlation-test");
            assertThat(query.get()).contains("granularity=DAY", "from=2026-08-01T00:00:00Z",
                    "to=2026-08-02T00:00:00Z");
        } finally {
            server.stop(0);
        }
    }
}
