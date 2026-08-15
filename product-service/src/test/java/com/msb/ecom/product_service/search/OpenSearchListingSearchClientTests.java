package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchListingSearchClientTests {

    private static final Instant GENERATION_TIME = Instant.parse("2026-07-23T01:02:03Z");
    private static final String GENERATION = "marketplace-listings-v1-g20260723010203000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void searchUsesReadAliasWithStructuredFiltersAndCursor() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        start(exchange -> {
            assertEquals("/marketplace-listings/_search", exchange.getRequestURI().getPath());
            requestBody.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {"hits":{"hits":[{"_id":"01LISTING0000000000000001"}]}}
                    """);
        });

        List<String> ids = client(false).searchIds(
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
                        List.of(),
                        List.of(),
                        new BigDecimal("42.00"),
                        Instant.parse("2026-06-01T12:00:00Z"),
                        "01CURSOR00000000000000001"),
                21);

        assertEquals(List.of("01LISTING0000000000000001"), ids);
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals(21, body.path("size").asInt());
        assertEquals(
                0,
                new BigDecimal("42.00").compareTo(body.path("search_after").get(0).decimalValue()));
        assertTrue(requestBody.get().contains("\"status\":\"ACTIVE\""));
        assertTrue(requestBody.get().contains("\"visibility\":\"PUBLIC\""));
    }

    @Test
    void mysqlDefaultDoesNotPerformHealthOrIndexRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            writeJson(exchange, 500, "{}");
        });
        ListingSearchProperties mysql = new ListingSearchProperties(
                "mysql",
                properties(true).opensearch());
        OpenSearchListingSearchClient client = new OpenSearchListingSearchClient(
                mysql,
                objectMapper,
                new ListingSearchProjectionMetrics(new SimpleMeterRegistry()),
                HttpClient.newHttpClient(),
                Clock.fixed(GENERATION_TIME, ZoneOffset.UTC));

        client.ensureIndex();

        assertFalse(client.enabled());
        assertEquals(0, requests.get());
    }

    @Test
    void absentProjectionCreatesSchemaIdentifiedBaselineAndWritesThroughWriteAlias() throws Exception {
        AtomicReference<String> indexBody = new AtomicReference<>();
        AtomicReference<String> documentBody = new AtomicReference<>();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_cluster/health")) {
                writeJson(exchange, 200, "{\"status\":\"yellow\"}");
            } else if (path.equals("/_alias/marketplace-listings")
                    || path.equals("/_alias/marketplace-listings-write")) {
                writeJson(exchange, 404, "{}");
            } else if (path.equals("/marketplace-listings-v1")
                    && exchange.getRequestMethod().equals("HEAD")) {
                writeJson(exchange, 404, "");
            } else if (path.equals("/marketplace-listings-v1")) {
                indexBody.set(readBody(exchange));
                writeJson(exchange, 200, "{\"acknowledged\":true}");
            } else if (path.equals("/marketplace-listings-write/_doc/01LISTING0000000000000001")) {
                documentBody.set(readBody(exchange));
                writeJson(exchange, 201, "{\"result\":\"created\"}");
            } else {
                writeJson(exchange, 500, "{}");
            }
        });

        client(true).upsert(document("01LISTING0000000000000001"));

        JsonNode definition = objectMapper.readTree(indexBody.get());
        assertEquals("strict", definition.path("mappings").path("dynamic").asText());
        assertEquals(
                OpenSearchListingSearchClient.SCHEMA_IDENTITY,
                definition.path("mappings").path("_meta").path("schemaIdentity").asText());
        assertTrue(definition.path("aliases").has("marketplace-listings"));
        assertTrue(definition.path("aliases").path("marketplace-listings-write").path("is_write_index").asBoolean());
        assertFalse(definition.path("mappings").path("properties").has("embedding"));

        JsonNode documentJson = objectMapper.readTree(documentBody.get());
        assertEquals("PUBLIC", documentJson.path("visibility").asText());
        assertEquals("ACTIVE", documentJson.path("status").asText());
        assertTrue(documentJson.path("inventoryAvailable").asBoolean());
        assertFalse(documentJson.has("ownerUserId"));
    }

    @Test
    void liveWritesUseAggregateExternalVersionAndTreatConflictsAsIdempotent() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        start(exchange -> {
            requests.incrementAndGet();
            query.set(exchange.getRequestURI().getQuery());
            writeJson(exchange, 409, "{\"error\":{\"type\":\"version_conflict_engine_exception\"}}");
        });

        client(false).upsert(document("01LISTING0000000000000001"), 11L);

        assertEquals(1, requests.get());
        assertEquals("version=11&version_type=external", query.get());
    }

    @Test
    void versionedDeleteTreatsMissingAndOlderDocumentsAsIdempotent() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        start(exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            writeJson(exchange, 404, "{\"result\":\"not_found\"}");
        });

        client(false).delete("01LISTING0000000000000001", 12L);

        assertEquals("version=12&version_type=external", query.get());
    }

    @Test
    void initializationRejectsMultiTargetAndIncompatibleAliases() throws Exception {
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_cluster/health")) {
                writeJson(exchange, 200, "{\"status\":\"yellow\"}");
            } else if (path.equals("/_alias/marketplace-listings")) {
                writeJson(exchange, 200, """
                        {
                          "marketplace-listings-v1":{"aliases":{"marketplace-listings":{}}},
                          "marketplace-listings-v1-other":{"aliases":{"marketplace-listings":{}}}
                        }
                        """);
            } else {
                writeJson(exchange, 500, "{}");
            }
        });
        assertThrows(ListingSearchUnavailableException.class, () -> client(true).ensureIndex());

        stopCurrentServer();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_cluster/health")) {
                writeJson(exchange, 200, "{\"status\":\"yellow\"}");
            } else if (path.equals("/_alias/marketplace-listings")) {
                writeJson(exchange, 200, aliasResponse("marketplace-listings-v1", "marketplace-listings"));
            } else if (path.equals("/_alias/marketplace-listings-write")) {
                writeJson(exchange, 200, aliasResponse("marketplace-listings-v1", "marketplace-listings-write"));
            } else if (path.equals("/marketplace-listings-v1/_mapping")) {
                writeJson(exchange, 200, mappingResponse("marketplace-listings-v1", "wrong-schema"));
            } else {
                writeJson(exchange, 500, "{}");
            }
        });
        assertThrows(ListingSearchUnavailableException.class, () -> client(true).ensureIndex());
    }

    @Test
    void promotedV2SupersetMappingIsCompatibleWithPublicLexicalSearch() throws Exception {
        AtomicReference<String> searchBody = new AtomicReference<>();
        String v2Generation = "marketplace-listings-v2-g20260723010203000";
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_cluster/health")) {
                writeJson(exchange, 200, "{\"status\":\"yellow\"}");
            } else if (path.equals("/_alias/marketplace-listings")) {
                writeJson(exchange, 200, aliasResponse(v2Generation, "marketplace-listings"));
            } else if (path.equals("/_alias/marketplace-listings-write")) {
                writeJson(exchange, 200, aliasResponse(v2Generation, "marketplace-listings-write"));
            } else if (path.equals("/" + v2Generation + "/_mapping")) {
                writeJson(exchange, 200, vectorMappingResponse(v2Generation));
            } else if (path.equals("/marketplace-listings/_search")) {
                searchBody.set(readBody(exchange));
                writeJson(exchange, 200, """
                        {"hits":{"hits":[{"_id":"01LISTING0000000000000001"}]}}
                        """);
            } else {
                writeJson(exchange, 500, "{}");
            }
        });

        List<String> ids = client(true).searchIds(
                "INDIVIDUAL",
                new PublicListingSearchCriteria(
                        "desk",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "newest",
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null),
                20);

        assertEquals(List.of("01LISTING0000000000000001"), ids);
        assertTrue(searchBody.get().contains("\"_source\":false"));
        assertTrue(searchBody.get().contains("\"sellerType\":\"INDIVIDUAL\""));
    }

    @Test
    void v2CompatibilityRejectsMismatchedIdentityUnknownVersionAndUnsafeBaselineFields() throws Exception {
        assertMappingRejected(mappingResponse(
                "marketplace-listings-v2-g20260723010203000",
                OpenSearchListingVectorBackfillClient.SCHEMA_VERSION,
                OpenSearchListingSearchClient.SCHEMA_IDENTITY,
                "strict",
                null,
                null));
        assertMappingRejected(mappingResponse(
                "marketplace-listings-v2-g20260723010203000",
                99,
                OpenSearchListingVectorBackfillClient.SCHEMA_IDENTITY,
                "strict",
                null,
                null));
        assertMappingRejected(mappingResponse(
                "marketplace-listings-v2-g20260723010203000",
                OpenSearchListingVectorBackfillClient.SCHEMA_VERSION,
                OpenSearchListingVectorBackfillClient.SCHEMA_IDENTITY,
                "false",
                null,
                null));
        assertMappingRejected(mappingResponse(
                "marketplace-listings-v2-g20260723010203000",
                OpenSearchListingVectorBackfillClient.SCHEMA_VERSION,
                OpenSearchListingVectorBackfillClient.SCHEMA_IDENTITY,
                "strict",
                "categoryName",
                null));
        assertMappingRejected(mappingResponse(
                "marketplace-listings-v2-g20260723010203000",
                OpenSearchListingVectorBackfillClient.SCHEMA_VERSION,
                OpenSearchListingVectorBackfillClient.SCHEMA_IDENTITY,
                "strict",
                null,
                "publicRegionKey"));
    }

    @Test
    void rebuildIndexesFreshGenerationValidatesCountAndAtomicallyPromotesBothAliases() throws Exception {
        AtomicBoolean promoted = new AtomicBoolean(false);
        AtomicReference<String> bulkBody = new AtomicReference<>();
        AtomicReference<String> promotionBody = new AtomicReference<>();
        AtomicReference<String> generationDefinition = new AtomicReference<>();
        start(exchange -> handleRebuild(
                exchange,
                promoted,
                bulkBody,
                promotionBody,
                generationDefinition,
                1,
                false,
                false));

        int indexed = client(true).rebuild(List.of(document("01LISTING0000000000000001")));

        assertEquals(1, indexed);
        assertTrue(promoted.get());
        assertTrue(bulkBody.get().contains("01LISTING0000000000000001"));
        assertFalse(bulkBody.get().contains("STALE"));
        JsonNode promotion = objectMapper.readTree(promotionBody.get());
        assertEquals(4, promotion.path("actions").size());
        assertEquals(
                GENERATION,
                promotion.path("actions").get(2).path("add").path("index").asText());
        assertEquals(
                "marketplace-listings-write",
                promotion.path("actions").get(3).path("add").path("alias").asText());
        JsonNode definition = objectMapper.readTree(generationDefinition.get());
        assertFalse(definition.has("aliases"));
    }

    @Test
    void rebuildValidationFailureDoesNotPromoteOrDisturbCurrentGeneration() throws Exception {
        AtomicBoolean promoted = new AtomicBoolean(false);
        AtomicReference<String> promotionBody = new AtomicReference<>();
        start(exchange -> handleRebuild(
                exchange,
                promoted,
                new AtomicReference<>(),
                promotionBody,
                new AtomicReference<>(),
                0,
                false,
                false));

        assertThrows(
                ListingSearchUnavailableException.class,
                () -> client(true).rebuild(List.of(document("01LISTING0000000000000001"))));
        assertFalse(promoted.get());
        assertEquals(null, promotionBody.get());
    }

    @Test
    void failedPromotionPreservesPreviousAliasesAndSuccessfulButInvalidPromotionRollsBack() throws Exception {
        AtomicBoolean promoted = new AtomicBoolean(false);
        AtomicInteger aliasWrites = new AtomicInteger();
        start(exchange -> handleRebuild(
                exchange,
                promoted,
                new AtomicReference<>(),
                new AtomicReference<>(),
                new AtomicReference<>(),
                1,
                true,
                false));
        assertThrows(
                ListingSearchUnavailableException.class,
                () -> client(true).rebuild(List.of(document("01LISTING0000000000000001"))));
        assertFalse(promoted.get());

        stopCurrentServer();
        promoted.set(false);
        start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/_aliases")) {
                aliasWrites.incrementAndGet();
            }
            handleRebuild(
                    exchange,
                    promoted,
                    new AtomicReference<>(),
                    new AtomicReference<>(),
                    new AtomicReference<>(),
                    1,
                    false,
                    true);
        });
        assertThrows(
                ListingSearchUnavailableException.class,
                () -> client(true).rebuild(List.of(document("01LISTING0000000000000001"))));
        assertEquals(2, aliasWrites.get());
        assertFalse(promoted.get());
    }

    @Test
    void duplicateOrUnsafeDocumentsFailBeforeCreatingGeneration() throws Exception {
        AtomicInteger generationChecks = new AtomicInteger();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/marketplace-listings-v1-g")) {
                generationChecks.incrementAndGet();
            }
            handleReadyProjection(exchange, false);
        });
        OpenSearchListingSearchClient client = client(true);

        assertThrows(
                ListingSearchUnavailableException.class,
                () -> client.rebuild(List.of(
                        document("01LISTING0000000000000001"),
                        document("01LISTING0000000000000001"))));
        assertEquals(0, generationChecks.get());
    }

    private void handleRebuild(
            HttpExchange exchange,
            AtomicBoolean promoted,
            AtomicReference<String> bulkBody,
            AtomicReference<String> promotionBody,
            AtomicReference<String> generationDefinition,
            int count,
            boolean failPromotion,
            boolean invalidateAfterPromotion) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/_cluster/health")) {
            writeJson(exchange, 200, "{\"status\":\"yellow\"}");
        } else if (path.equals("/_alias/marketplace-listings")) {
            String target = promoted.get() ? GENERATION : "marketplace-listings-v1";
            if (invalidateAfterPromotion && promoted.get()) {
                writeJson(exchange, 200, """
                        {
                          "marketplace-listings-v1":{"aliases":{"marketplace-listings":{}}},
                          "%s":{"aliases":{"marketplace-listings":{}}}
                        }
                        """.formatted(GENERATION));
            } else {
                writeJson(exchange, 200, aliasResponse(target, "marketplace-listings"));
            }
        } else if (path.equals("/_alias/marketplace-listings-write")) {
            String target = promoted.get() ? GENERATION : "marketplace-listings-v1";
            writeJson(exchange, 200, aliasResponse(target, "marketplace-listings-write"));
        } else if (path.equals("/marketplace-listings-v1/_mapping")) {
            writeJson(
                    exchange,
                    200,
                    mappingResponse("marketplace-listings-v1", OpenSearchListingSearchClient.SCHEMA_IDENTITY));
        } else if (path.equals("/" + GENERATION) && exchange.getRequestMethod().equals("HEAD")) {
            writeJson(exchange, 404, "");
        } else if (path.equals("/" + GENERATION) && exchange.getRequestMethod().equals("PUT")) {
            generationDefinition.set(readBody(exchange));
            writeJson(exchange, 200, "{\"acknowledged\":true}");
        } else if (path.equals("/" + GENERATION + "/_mapping")) {
            writeJson(
                    exchange,
                    200,
                    mappingResponse(GENERATION, OpenSearchListingSearchClient.SCHEMA_IDENTITY));
        } else if (path.equals("/_bulk")) {
            bulkBody.set(readBody(exchange));
            writeJson(exchange, 200, "{\"errors\":false,\"items\":[]}");
        } else if (path.equals("/" + GENERATION + "/_refresh")) {
            writeJson(exchange, 200, "{\"_shards\":{\"failed\":0}}");
        } else if (path.equals("/" + GENERATION + "/_count")) {
            writeJson(exchange, 200, "{\"count\":" + count + "}");
        } else if (path.equals("/_aliases")) {
            String body = readBody(exchange);
            if (!promoted.get()) {
                promotionBody.set(body);
                if (failPromotion) {
                    writeJson(exchange, 500, "{}");
                    return;
                }
                promoted.set(true);
                writeJson(exchange, 200, "{\"acknowledged\":true}");
            } else {
                promoted.set(false);
                writeJson(exchange, 200, "{\"acknowledged\":true}");
            }
        } else {
            writeJson(exchange, 500, "{}");
        }
    }

    private void handleReadyProjection(HttpExchange exchange, boolean promoted) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String target = promoted ? GENERATION : "marketplace-listings-v1";
        if (path.equals("/_cluster/health")) {
            writeJson(exchange, 200, "{\"status\":\"yellow\"}");
        } else if (path.equals("/_alias/marketplace-listings")) {
            writeJson(exchange, 200, aliasResponse(target, "marketplace-listings"));
        } else if (path.equals("/_alias/marketplace-listings-write")) {
            writeJson(exchange, 200, aliasResponse(target, "marketplace-listings-write"));
        } else if (path.equals("/" + target + "/_mapping")) {
            writeJson(exchange, 200, mappingResponse(target, OpenSearchListingSearchClient.SCHEMA_IDENTITY));
        } else {
            writeJson(exchange, 500, "{}");
        }
    }

    private String aliasResponse(String index, String alias) {
        String definition = alias.endsWith("-write") ? "{\"is_write_index\":true}" : "{}";
        return """
                {"%s":{"aliases":{"%s":%s}}}
                """.formatted(index, alias, definition);
    }

    private String mappingResponse(String index, String identity) throws IOException {
        return mappingResponse(
                index,
                OpenSearchListingSearchClient.SCHEMA_VERSION,
                identity,
                "strict",
                null,
                null);
    }

    private String mappingResponse(
            String index,
            int schemaVersion,
            String identity,
            String dynamic,
            String missingField,
            String wrongField) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode mappings = root.putObject(index).putObject("mappings");
        mappings.put("dynamic", dynamic);
        mappings.putObject("_meta")
                .put("projection", OpenSearchListingSearchClient.PROJECTION_NAME)
                .put("schemaVersion", schemaVersion)
                .put("schemaIdentity", identity);
        ObjectNode fields = mappings.putObject("properties");
        field(fields, "listingId", "keyword");
        field(fields, "sellerType", "keyword");
        field(fields, "title", "text");
        field(fields, "description", "text");
        field(fields, "searchText", "text");
        field(fields, "categoryId", "keyword");
        field(fields, "categorySlug", "keyword");
        field(fields, "categoryName", "text");
        field(fields, "condition", "keyword");
        field(fields, "priceAmount", "double");
        field(fields, "currency", "keyword");
        field(fields, "publicCity", "keyword");
        field(fields, "publicCityKey", "keyword");
        field(fields, "publicRegion", "keyword");
        field(fields, "publicRegionKey", "keyword");
        field(fields, "status", "keyword");
        field(fields, "visibility", "keyword");
        field(fields, "inventoryAvailable", "boolean");
        field(fields, "publishedAt", "date");
        if (missingField != null) {
            fields.remove(missingField);
        }
        if (wrongField != null) {
            fields.putObject(wrongField).put("type", "long");
        }
        return objectMapper.writeValueAsString(root);
    }

    private String vectorMappingResponse(String index) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode indexNode = root.putObject(index);
        indexNode.set(
                "mappings",
                objectMapper.readTree(vectorClient().indexDefinitionJson()).path("mappings"));
        return objectMapper.writeValueAsString(root);
    }

    private void assertMappingRejected(String mapping) throws Exception {
        stopCurrentServer();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_cluster/health")) {
                writeJson(exchange, 200, "{\"status\":\"yellow\"}");
            } else if (path.equals("/_alias/marketplace-listings")) {
                writeJson(exchange, 200, aliasResponse("marketplace-listings-v2-g20260723010203000",
                        "marketplace-listings"));
            } else if (path.equals("/_alias/marketplace-listings-write")) {
                writeJson(exchange, 200, aliasResponse("marketplace-listings-v2-g20260723010203000",
                        "marketplace-listings-write"));
            } else if (path.equals("/marketplace-listings-v2-g20260723010203000/_mapping")) {
                writeJson(exchange, 200, mapping);
            } else {
                writeJson(exchange, 500, "{}");
            }
        });
        assertThrows(ListingSearchUnavailableException.class, () -> client(true).ensureIndex());
    }

    private void field(ObjectNode fields, String name, String type) {
        fields.putObject(name).put("type", type);
    }

    private ListingSearchDocument document(String listingId) {
        return new ListingSearchDocument(
                listingId,
                "INDIVIDUAL",
                "01CATEGORY00000000000001",
                "general",
                "General",
                "Desk",
                "Wood desk",
                "Desk Wood desk General general GOOD Irvine Orange County",
                "GOOD",
                new BigDecimal("42.00"),
                "USD",
                "Irvine",
                "irvine",
                "Orange County",
                "orange county",
                Instant.parse("2026-07-01T00:00:00Z"),
                true,
                "/api/v1/public/listing-media/01IMAGE000000000000000001");
    }

    private OpenSearchListingSearchClient client(boolean initializeIndex) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new OpenSearchListingSearchClient(
                properties(initializeIndex),
                objectMapper,
                new ListingSearchProjectionMetrics(registry),
                HttpClient.newHttpClient(),
                Clock.fixed(GENERATION_TIME, ZoneOffset.UTC));
    }

    private ListingSearchProperties properties(boolean initializeIndex) {
        return new ListingSearchProperties(
                "opensearch",
                new ListingSearchProperties.OpenSearchProperties(
                        "http://localhost:" + server.getAddress().getPort(),
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(3),
                        initializeIndex));
    }

    private OpenSearchListingVectorBackfillClient vectorClient() {
        return new OpenSearchListingVectorBackfillClient(
                properties(false),
                new ListingVectorBackfillProperties(
                        false,
                        "marketplace-listings-v2",
                        25,
                        100,
                        5_000_000,
                        true),
                objectMapper,
                new ListingSearchProjectionMetrics(new SimpleMeterRegistry()),
                HttpClient.newHttpClient(),
                Clock.fixed(GENERATION_TIME, ZoneOffset.UTC));
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
    }

    private void stopCurrentServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
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
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
