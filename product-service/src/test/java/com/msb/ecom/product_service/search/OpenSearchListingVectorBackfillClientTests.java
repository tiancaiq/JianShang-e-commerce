package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchListingVectorBackfillClientTests {

    private static final Instant NOW = Instant.parse("2026-07-23T10:11:12.123Z");
    private static final String GENERATION = "marketplace-listings-v2-g20260723101112123";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsAndValidatesInactiveMixedGenerationWithoutAliasMutation() throws Exception {
        AtomicReference<String> definition = new AtomicReference<>();
        AtomicReference<String> bulk = new AtomicReference<>();
        AtomicBoolean indexed = new AtomicBoolean(false);
        AtomicInteger aliasMutations = new AtomicInteger();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_alias/marketplace-listings")
                    || path.equals("/_alias/marketplace-listings-write")) {
                write(exchange, 200, alias(path.substring(path.lastIndexOf('/') + 1)));
            } else if (path.equals("/_cluster/health")) {
                write(exchange, 200, "{\"status\":\"yellow\"}");
            } else if (path.equals("/" + GENERATION) && exchange.getRequestMethod().equals("HEAD")) {
                write(exchange, 404, "");
            } else if (path.equals("/" + GENERATION) && exchange.getRequestMethod().equals("PUT")) {
                definition.set(read(exchange));
                write(exchange, 200, "{\"acknowledged\":true}");
            } else if (path.equals("/" + GENERATION + "/_mapping")) {
                ObjectNode root = objectMapper.createObjectNode();
                root.putObject(GENERATION).set(
                        "mappings",
                        objectMapper.readTree(definition.get()).path("mappings"));
                write(exchange, 200, root.toString());
            } else if (path.equals("/_bulk")) {
                indexed.set(true);
                bulk.set(read(exchange));
                write(exchange, 200, "{\"errors\":false,\"items\":[]}");
            } else if (path.equals("/" + GENERATION + "/_refresh")) {
                write(exchange, 200, "{\"_shards\":{\"failed\":0}}");
            } else if (path.equals("/" + GENERATION + "/_count")) {
                boolean vectorCount = exchange.getRequestMethod().equals("POST");
                write(exchange, 200, "{\"count\":" + (indexed.get() ? (vectorCount ? 1 : 2) : 0) + "}");
            } else if (path.equals("/_aliases")) {
                aliasMutations.incrementAndGet();
                write(exchange, 500, "{}");
            } else {
                write(exchange, 500, "{}");
            }
        });

        var result = client(true).backfill(List.of(document("01L00000000000000000000501", false),
                document("01L00000000000000000000502", true)));

        assertThat(result.generation()).isEqualTo(GENERATION);
        assertThat(result.documentCount()).isEqualTo(2);
        assertThat(result.vectorDocumentCount()).isEqualTo(1);
        assertThat(aliasMutations).hasValue(0);
        JsonNode mapping = objectMapper.readTree(definition.get());
        assertThat(mapping.path("settings").path("index.knn").asBoolean()).isTrue();
        assertThat(mapping.path("mappings").path("_source").path("excludes").get(0).asText())
                .isEqualTo("embedding");
        JsonNode vector = mapping.path("mappings").path("properties").path("embedding");
        assertThat(vector.path("dimension").asInt()).isEqualTo(1536);
        assertThat(vector.path("method").path("engine").asText()).isEqualTo("lucene");
        assertThat(vector.path("method").path("space_type").asText()).isEqualTo("cosinesimil");
        assertThat(vector.path("method").path("parameters").path("m").asInt()).isEqualTo(16);
        assertThat(vector.path("method").path("parameters").path("ef_construction").asInt())
                .isEqualTo(100);
        assertThat(bulk.get()).contains("\"version\":15", "\"version\":16", "\"embedding\"");
        assertThat(bulk.get()).doesNotContain("ownerUserId", "sellerId", "email", "phone", "moderation");
    }

    @Test
    void defaultOffPerformsNoOpenSearchRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            write(exchange, 500, "{}");
        });

        OpenSearchListingVectorBackfillClient client = client(false);
        assertThat(client.vectorWritesAvailable()).isTrue();
        assertThat(client.enabled()).isFalse();
        assertThatThrownBy(() -> client.backfill(List.of()))
                .isInstanceOf(ListingSearchUnavailableException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void promotesBothStableAliasesInOneRequestAndSupportsBoundedRollback() throws Exception {
        AtomicReference<String> target = new AtomicReference<>("marketplace-listings-v1");
        AtomicReference<String> aliasRequest = new AtomicReference<>();
        AtomicInteger aliasMutations = new AtomicInteger();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_alias/marketplace-listings")
                    || path.equals("/_alias/marketplace-listings-write")) {
                String alias = path.substring(path.lastIndexOf('/') + 1);
                write(exchange, 200, alias(target.get(), alias));
            } else if (path.equals("/" + GENERATION + "/_alias")) {
                write(exchange, 200, "{\"" + GENERATION + "\":{\"aliases\":{}}}");
            } else if (path.equals("/_aliases")) {
                aliasMutations.incrementAndGet();
                aliasRequest.set(read(exchange));
                target.set(target.get().equals("marketplace-listings-v1")
                        ? GENERATION
                        : "marketplace-listings-v1");
                write(exchange, 200, "{\"acknowledged\":true}");
            } else {
                write(exchange, 500, "{}");
            }
        });
        OpenSearchListingVectorBackfillClient client = client(true);

        client.promoteAliases("marketplace-listings-v1", GENERATION);

        assertThat(target).hasValue(GENERATION);
        assertThat(aliasMutations).hasValue(1);
        assertThat(aliasRequest.get())
                .contains("\"remove\"", "\"add\"", "\"is_write_index\":true")
                .contains("marketplace-listings-write");

        client.rollbackAliases(GENERATION, "marketplace-listings-v1");

        assertThat(target).hasValue("marketplace-listings-v1");
        assertThat(aliasMutations).hasValue(2);
    }

    @Test
    void routesVectorsOnlyToCompatibleV2AndDeduplicatesPromotedCandidate() throws Exception {
        AtomicReference<String> definition = new AtomicReference<>();
        AtomicReference<String> active = new AtomicReference<>("marketplace-listings-v1");
        AtomicReference<String> vectorWrite = new AtomicReference<>();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_cluster/health")) {
                write(exchange, 200, "{\"status\":\"yellow\"}");
            } else if (path.equals("/" + GENERATION)
                    && exchange.getRequestMethod().equals("HEAD")) {
                write(exchange, 404, "");
            } else if (path.equals("/" + GENERATION)
                    && exchange.getRequestMethod().equals("PUT")) {
                definition.set(read(exchange));
                write(exchange, 200, "{\"acknowledged\":true}");
            } else if (path.equals("/" + GENERATION + "/_mapping")) {
                ObjectNode root = objectMapper.createObjectNode();
                root.putObject(GENERATION).set(
                        "mappings",
                        objectMapper.readTree(definition.get()).path("mappings"));
                write(exchange, 200, root.toString());
            } else if (path.equals("/" + GENERATION + "/_count")) {
                write(exchange, 200, "{\"count\":0}");
            } else if (path.equals("/" + GENERATION + "/_alias")) {
                write(exchange, 200, "{\"" + GENERATION + "\":{\"aliases\":{}}}");
            } else if (path.equals("/_alias/marketplace-listings")
                    || path.equals("/_alias/marketplace-listings-write")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                write(exchange, 200, alias(active.get(), name));
            } else if (path.equals("/" + GENERATION + "/_doc/01L00000000000000000000502")) {
                vectorWrite.set(exchange.getRequestURI().getQuery() + "\n" + read(exchange));
                write(exchange, 200, "{\"result\":\"updated\"}");
            } else {
                write(exchange, 500, "{}");
            }
        });
        OpenSearchListingVectorBackfillClient client = client(true);
        client.createInactiveGeneration(GENERATION);

        var v1WithCandidate = client.resolveVectorTargets(Optional.of(GENERATION));
        assertThat(v1WithCandidate.generations()).containsExactly(GENERATION);
        client.upsertVectorTarget(
                v1WithCandidate.generations().get(0),
                document("01L00000000000000000000502", true));
        assertThat(vectorWrite.get()).contains("version=16", "\"embedding\"");

        active.set(GENERATION);
        assertThat(client.resolveVectorTargets(Optional.of(GENERATION)).generations())
                .containsExactly(GENERATION);
    }

    private ListingVectorBackfillDocument document(String id, boolean vector) {
        float[] embedding = vector ? new float[1536] : null;
        if (embedding != null) {
            java.util.Arrays.fill(embedding, 0.25f);
        }
        return new ListingVectorBackfillDocument(
                new ListingSearchDocument(
                        id,
                        "INDIVIDUAL",
                        "01C00000000000000000000501",
                        "furniture",
                        "Furniture",
                        "Desk",
                        "Public desk",
                        "Desk Public desk Furniture furniture GOOD Irvine Orange County",
                        "GOOD",
                        new BigDecimal("25.00"),
                        "USD",
                        "Irvine",
                        "irvine",
                        "Orange County",
                        "orange county",
                        NOW,
                        true,
                        "/api/v1/public/listing-media/01I00000000000000000000501"),
                7,
                "MARKETPLACE_LISTING_DISCOVERY_V2",
                "a".repeat(64),
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
                "b".repeat(64),
                "NFKC_WHITESPACE_V1",
                "PUBLIC_CONTACT_REDACTION_V1",
                "und",
                "openai",
                "text-embedding-3-small",
                1536,
                vector ? "ATTACHED" : "MISSING",
                embedding);
    }

    private OpenSearchListingVectorBackfillClient client(boolean enabled) {
        ListingSearchProperties search = new ListingSearchProperties(
                "opensearch",
                new ListingSearchProperties.OpenSearchProperties(
                        "http://localhost:" + server.getAddress().getPort(),
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        false));
        return new OpenSearchListingVectorBackfillClient(
                search,
                new ListingVectorBackfillProperties(
                        enabled,
                        "marketplace-listings-v2",
                        100,
                        1000,
                        5_000_000,
                        true),
                objectMapper,
                new ListingSearchProjectionMetrics(new SimpleMeterRegistry()),
                HttpClient.newHttpClient(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String alias(String name) {
        return alias("marketplace-listings-v1", name);
    }

    private String alias(String target, String name) {
        String options = name.endsWith("-write") ? "{\"is_write_index\":true}" : "{}";
        return "{\"" + target + "\":{\"aliases\":{\"" + name + "\":" + options + "}}}";
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    private String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
