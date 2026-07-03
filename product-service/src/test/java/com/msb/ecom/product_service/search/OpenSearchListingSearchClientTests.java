package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchListingSearchClientTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchIdsBuildsFiltersSortAndCursor() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/msb-public-listings/_search", exchange -> {
            requestBody.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {"hits":{"hits":[{"_id":"01LISTING0000000000000001"}]}}
                    """);
        });
        server.start();

        OpenSearchListingSearchClient client = new OpenSearchListingSearchClient(
                properties("http://localhost:" + server.getAddress().getPort(), false),
                objectMapper);

        List<String> ids = client.searchIds(
                "INDIVIDUAL",
                new PublicListingSearchCriteria(
                        "bike",
                        "01CATEGORY00000000000001",
                        "GOOD",
                        new BigDecimal("10.00"),
                        new BigDecimal("80.00"),
                        "Irvine",
                        "Orange County",
                        "price_asc",
                        new BigDecimal("12.00"),
                        Instant.parse("2026-07-01T00:00:00Z"),
                        "01CURSOR000000000000001"),
                25);

        assertEquals(List.of("01LISTING0000000000000001"), ids);
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals(25, body.path("size").asInt());
        assertEquals("priceAmount", body.path("sort").get(0).fieldNames().next());
        assertEquals(0, new BigDecimal("12.00").compareTo(body.path("search_after").get(0).decimalValue()));
        String json = requestBody.get();
        assertTrue(json.contains("\"sellerType\":\"INDIVIDUAL\""));
        assertTrue(json.contains("\"publicCityKey\":\"irvine\""));
        assertTrue(json.contains("\"publicRegionKey\":\"orange county\""));
        assertTrue(json.contains("\"multi_match\""));
    }

    @Test
    void upsertCreatesIndexWhenEnabled() throws Exception {
        AtomicReference<String> documentBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/msb-public-listings", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, "{\"acknowledged\":true}");
        });
        server.createContext("/msb-public-listings/_doc/01LISTING0000000000000001", exchange -> {
            documentBody.set(readBody(exchange));
            writeJson(exchange, 201, "{\"result\":\"created\"}");
        });
        server.start();

        OpenSearchListingSearchClient client = new OpenSearchListingSearchClient(
                properties("http://localhost:" + server.getAddress().getPort(), true),
                objectMapper);

        client.upsert(new ListingSearchDocument(
                "01LISTING0000000000000001",
                "BUSINESS",
                "01CATEGORY00000000000001",
                "general",
                "General",
                "Desk",
                "Wood desk",
                "GOOD",
                new BigDecimal("42.00"),
                "USD",
                "Irvine",
                "irvine",
                "Orange County",
                "orange county",
                Instant.parse("2026-07-01T00:00:00Z")));

        JsonNode body = objectMapper.readTree(documentBody.get());
        assertEquals("Desk", body.path("title").asText());
        assertEquals("BUSINESS", body.path("sellerType").asText());
        assertEquals("orange county", body.path("publicRegionKey").asText());
    }

    private ListingSearchProperties properties(String baseUrl, boolean initializeIndex) {
        return new ListingSearchProperties(
                "opensearch",
                new ListingSearchProperties.OpenSearchProperties(
                        baseUrl,
                        "msb-public-listings",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        initializeIndex));
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
