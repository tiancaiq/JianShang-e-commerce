package com.msb.ecom.product_service.search.hybrid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.ListingVectorBackfillProperties;
import com.msb.ecom.product_service.search.OpenSearchListingVectorBackfillClient;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSearchListingHybridSearchClientTests {

    private static final String GENERATION =
            "marketplace-listings-v2-g20260723101112123";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void validatesV2AliasAndBuildsFixedBm25AndFilteredKnnBranches() throws Exception {
        AtomicReference<String> lexical = new AtomicReference<>();
        AtomicReference<String> vector = new AtomicReference<>();
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_alias/marketplace-listings")) {
                write(exchange, 200, alias());
            } else if (path.equals("/" + GENERATION + "/_mapping")) {
                write(exchange, 200, mapping("marketplace-public-listing-v2-vector"));
            } else if (path.equals("/" + GENERATION + "/_settings")) {
                write(exchange, 200, settings());
            } else if (path.equals("/marketplace-listings/_search")) {
                String body = read(exchange);
                if (body.contains("\"knn\"")) {
                    vector.set(body);
                    write(exchange, 200,
                            hits("01D00000000000000000000102"));
                } else {
                    lexical.set(body);
                    write(exchange, 200,
                            hits("01D00000000000000000000101"));
                }
            } else {
                write(exchange, 500, "{}");
            }
        });
        OpenSearchListingHybridSearchClient client = client();
        ListingHybridSearchRequest request = request();

        client.validateReadAlias();
        assertThat(client.searchLexical(request))
                .containsExactly("01D00000000000000000000101");
        assertThat(client.searchVector(request))
                .containsExactly("01D00000000000000000000102");

        JsonNode lexicalJson = mapper.readTree(lexical.get());
        assertThat(lexicalJson.path("size").asInt()).isEqualTo(60);
        assertThat(lexicalJson.path("_source").asBoolean()).isFalse();
        assertThat(lexical.get())
                .contains("title^4", "categoryName^2", "inventoryAvailable",
                        "publicCityKey", "publicRegionKey", "priceAmount")
                .doesNotContain("ownerUserId", "sellerId", "query_string", "\"script\"");
        JsonNode vectorJson = mapper.readTree(vector.get());
        JsonNode knn = vectorJson.path("query").path("knn").path("embedding");
        assertThat(knn.path("k").asInt()).isEqualTo(60);
        assertThat(knn.path("vector")).hasSize(1536);
        assertThat(vector.get()).contains("\"filter\"", "\"sellerType\":\"INDIVIDUAL\"");
    }

    @Test
    void v4LexicalBranchUsesBoundedSynonymCandidatesWithAndSemantics() throws Exception {
        ListingHybridSearchRequest base = request();
        ListingHybridSearchRequest request = new ListingHybridSearchRequest(
                "key bag", base.embedding(), base.filters(), 10,
                ListingHybridSearchResponse.CONCEPT_RERANK_SCHEMA_VERSION);

        JsonNode lexical = mapper.readTree(client().lexicalBody(request));

        JsonNode bool = lexical.path("query").path("bool");
        assertThat(bool.path("minimum_should_match").asInt()).isEqualTo(1);
        assertThat(bool.path("should")).hasSize(5);
        assertThat(bool.toString())
                .contains("key bag", "key pouch", "key case", "key wallet", "key organizer")
                .contains("\"operator\":\"and\"")
                .doesNotContain("tote bag", "crossbody bag", "sling bag");
    }

    @Test
    void rejectsIncompatibleMappingBeforeAnyBranchSearch() throws Exception {
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_alias/marketplace-listings")) {
                write(exchange, 200, alias());
            } else if (path.equals("/" + GENERATION + "/_mapping")) {
                write(exchange, 200, mapping("marketplace-public-listing-v1-bm25"));
            } else if (path.equals("/" + GENERATION + "/_settings")) {
                write(exchange, 200, settings());
            } else {
                write(exchange, 500, "{}");
            }
        });

        assertThatThrownBy(() -> client().validateReadAlias())
                .isInstanceOf(ListingHybridSearchIdentityMismatchException.class);
    }

    @Test
    void vectorBranchUsesValidatedPhysicalGenerationWhenProvided() throws Exception {
        start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_alias/marketplace-listings")) {
                write(exchange, 200, alias());
            } else if (path.equals("/" + GENERATION + "/_mapping")) {
                write(exchange, 200, mapping("marketplace-public-listing-v2-vector"));
            } else if (path.equals("/" + GENERATION + "/_settings")) {
                write(exchange, 200, settings());
            } else if (path.equals("/" + GENERATION + "/_search")) {
                String body = read(exchange);
                assertThat(body).contains("\"knn\"");
                write(exchange, 200, hits("01D00000000000000000000101"));
            } else if (path.equals("/marketplace-listings/_search")) {
                write(exchange, 500, "{}");
            } else {
                write(exchange, 500, "{}");
            }
        });
        OpenSearchListingHybridSearchClient client = client();
        String target = client.validateReadAlias();

        assertThat(client.searchVector(target, request()))
                .containsExactly("01D00000000000000000000101");
    }

    @Test
    void acceptsOpaqueProductListingIdsAndDeduplicatesWithoutNormalization() throws Exception {
        start(exchange -> write(exchange, 200, hits(
                "81D00000000000000000000101",
                "Z1D00000000000000000000102",
                "01D00000000000000000000103",
                "81D00000000000000000000101")));

        assertThat(client().searchVector(GENERATION, request()))
                .containsExactly(
                        "81D00000000000000000000101",
                        "Z1D00000000000000000000102",
                        "01D00000000000000000000103");
    }

    @Test
    void rejectsMalformedOpaqueProductListingHitIds() throws Exception {
        for (String id : List.of(
                "81d00000000000000000000101",
                "81D0000000000000000000010I",
                "81D0000000000000000000010L",
                "81D0000000000000000000010O",
                "81D0000000000000000000010U",
                "81D0000000000000000000010",
                "81D000000000000000000001010",
                " 81D00000000000000000000101",
                "81D0000000000000000000010*")) {
            stopServer();
            start(exchange -> write(exchange, 200, hits(id)));

            assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                    .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                            exception -> assertThat(exception.kind())
                                    .isEqualTo(ListingHybridSearchFailureKind.HIT_ID_INVALID));
        }
    }

    @Test
    void rejectsNullOrMissingHitIds() throws Exception {
        start(exchange -> write(exchange, 200,
                "{\"hits\":{\"hits\":[{\"_id\":null}]}}"));
        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.HIT_ID_INVALID));

        stopServer();
        start(exchange -> write(exchange, 200,
                "{\"hits\":{\"hits\":[{}]}}"));
        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.HIT_ID_INVALID));
    }

    @Test
    void classifiesHttpFailureKindsWithoutResponseDetails() throws Exception {
        start(exchange -> write(exchange, 429, "{\"secret\":\"sk-test\"}"));

        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.HTTP_4XX));

        stopServer();
        start(exchange -> write(exchange, 503, "{\"secret\":\"sk-test\"}"));
        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.HTTP_5XX));
    }

    @Test
    void classifiesInvalidResponseBodiesAndHits() throws Exception {
        start(exchange -> write(exchange, 200, "not-json"));
        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.RESPONSE_JSON_INVALID));

        stopServer();
        start(exchange -> write(exchange, 200, "x".repeat(
                OpenSearchListingHybridSearchClient.MAX_RESPONSE_BYTES + 1)));
        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.RESPONSE_OVERSIZE_OR_MISSING));

        stopServer();
        start(exchange -> write(exchange, 200, "{\"hits\":{\"hits\":{}}}"));
        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.HITS_SHAPE_INVALID));

        stopServer();
        start(exchange -> write(exchange, 200, hits("not-a-product-id")));
        assertThatThrownBy(() -> client().searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.HIT_ID_INVALID));
    }

    @Test
    void classifiesTransportFailuresWithoutExceptionDetails() {
        HttpClient ioClient = new ThrowingHttpClient(new IOException("secret token sk-test"));
        assertThatThrownBy(() -> client(ioClient).searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.TRANSPORT_IO));

        HttpClient interrupted = new ThrowingHttpClient(new InterruptedException("secret vector"));
        assertThatThrownBy(() -> client(interrupted).searchVector(GENERATION, request()))
                .isInstanceOfSatisfying(ListingHybridSearchUnavailableException.class,
                        exception -> assertThat(exception.kind())
                                .isEqualTo(ListingHybridSearchFailureKind.TRANSPORT_INTERRUPTED));
        assertThat(Thread.interrupted()).isTrue();
    }

    private ListingHybridSearchRequest request() {
        return new ListingHybridSearchRequest(
                "desk chair",
                new float[1536],
                new ListingHybridSearchRequest.Filters(
                        "01D00000000000000000000101",
                        "GOOD",
                        new BigDecimal("10.00"),
                        new BigDecimal("100.00"),
                        "USD",
                        "Irvine",
                        "Orange County"),
                10);
    }

    private OpenSearchListingHybridSearchClient client() {
        return client(HttpClient.newHttpClient());
    }

    private OpenSearchListingHybridSearchClient client(HttpClient httpClient) {
        ListingSearchProperties properties = new ListingSearchProperties(
                "opensearch",
                new ListingSearchProperties.OpenSearchProperties(
                        server == null
                                ? "http://127.0.0.1:1"
                                : "http://localhost:" + server.getAddress().getPort(),
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        false));
        ListingVectorBackfillProperties backfill = new ListingVectorBackfillProperties(
                false,
                "marketplace-listings-v2",
                100,
                100_000,
                5_000_000,
                true);
        return new OpenSearchListingHybridSearchClient(
                properties,
                backfill,
                mapper,
                httpClient);
    }

    private String alias() {
        return "{\"" + GENERATION + "\":{\"aliases\":{\"marketplace-listings\":{}}}}";
    }

    private String mapping(String identity) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode mappings = root.putObject(GENERATION).putObject("mappings");
        mappings.put("dynamic", "strict");
        mappings.putObject("_source").putArray("excludes").add("embedding");
        mappings.putObject("_meta")
                .put("schemaVersion", OpenSearchListingVectorBackfillClient.SCHEMA_VERSION)
                .put("schemaIdentity", identity)
                .put("embeddingProvider", ListingDiscoveryEmbeddingSourceBuilder.PROVIDER)
                .put("embeddingModel", ListingDiscoveryEmbeddingSourceBuilder.MODEL)
                .put("embeddingDimensions", ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS)
                .put("engine", OpenSearchListingVectorBackfillClient.ENGINE)
                .put("distanceSpace", OpenSearchListingVectorBackfillClient.DISTANCE_SPACE);
        ObjectNode embedding = mappings.putObject("properties").putObject("embedding");
        ObjectNode properties = mappings.withObject("/properties");
        keyword(properties, "listingId");
        keyword(properties, "sellerType");
        keyword(properties, "categoryId");
        text(properties, "categoryName");
        text(properties, "title");
        text(properties, "description");
        text(properties, "searchText");
        keyword(properties, "condition");
        properties.putObject("priceAmount").put("type", "double");
        keyword(properties, "currency");
        keyword(properties, "publicCityKey");
        keyword(properties, "publicRegionKey");
        keyword(properties, "status");
        keyword(properties, "visibility");
        properties.putObject("inventoryAvailable").put("type", "boolean");
        embedding.put("type", "knn_vector");
        embedding.put("dimension", 1536);
        embedding.putObject("method")
                .put("engine", "lucene")
                .put("space_type", "cosinesimil");
        return root.toString();
    }

    private String settings() {
        return "{\"" + GENERATION + "\":{\"settings\":{\"index\":{\"knn\":\"true\"}}}}";
    }

    private void keyword(ObjectNode fields, String name) {
        fields.putObject(name).put("type", "keyword");
    }

    private void text(ObjectNode fields, String name) {
        fields.putObject(name).put("type", "text");
    }

    private String hits(String... ids) {
        StringBuilder builder = new StringBuilder("{\"hits\":{\"hits\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("{\"_id\":\"").append(ids[i]).append("\"}");
        }
        return builder.append("]}}").toString();
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class ThrowingHttpClient extends HttpClient {
        private final Exception exception;

        private ThrowingHttpClient(Exception exception) {
            this.exception = exception;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            if (exception instanceof IOException io) {
                throw io;
            }
            throw (InterruptedException) exception;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }
}
