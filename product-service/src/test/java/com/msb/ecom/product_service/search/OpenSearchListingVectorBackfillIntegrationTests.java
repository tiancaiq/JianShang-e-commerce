package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OpenSearchListingVectorBackfillIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-23T10:11:12.123Z");
    private static final String GENERATION = "marketplace-listings-v2-g20260723101112123";

    @Container
    static GenericContainer<?> openSearch = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.15.0"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void realOpenSearchAcceptsVectorMappingAndKeepsActiveAliasesOnV1() throws Exception {
        String baseUrl = "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200);
        require2xx(send(baseUrl, "PUT", "/marketplace-listings-v1", """
                {"settings":{"index.number_of_shards":1,"index.number_of_replicas":0},
                 "aliases":{"marketplace-listings":{},
                            "marketplace-listings-write":{"is_write_index":true}}}
                """));
        ListingSearchProperties search = new ListingSearchProperties(
                "opensearch",
                new ListingSearchProperties.OpenSearchProperties(
                        baseUrl,
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        false));
        OpenSearchListingVectorBackfillClient client = new OpenSearchListingVectorBackfillClient(
                search,
                new ListingVectorBackfillProperties(
                        true, "marketplace-listings-v2", 25, 100, 5_000_000, true),
                objectMapper,
                new ListingSearchProjectionMetrics(new SimpleMeterRegistry()),
                http,
                Clock.fixed(NOW, ZoneOffset.UTC));

        String zeroLexicalId = "01L00000000000000000000521";
        String zeroVectorId = "01L00000000000000000000522";
        var result = client.backfill(List.of(document(zeroLexicalId, 0, false),
                document(zeroVectorId, 0, true)));

        assertThat(result.generation()).isEqualTo(GENERATION);
        JsonNode readAlias = json(send(baseUrl, "GET", "/_alias/marketplace-listings", null));
        JsonNode writeAlias = json(send(baseUrl, "GET", "/_alias/marketplace-listings-write", null));
        assertThat(readAlias.has("marketplace-listings-v1")).isTrue();
        assertThat(writeAlias.has("marketplace-listings-v1")).isTrue();
        assertThat(readAlias.has(GENERATION)).isFalse();
        assertThat(writeAlias.has(GENERATION)).isFalse();

        JsonNode searchResult = json(send(baseUrl, "POST", "/" + GENERATION + "/_search", """
                {"size":10,"query":{"match_all":{}}}
                """));
        assertThat(searchResult.path("hits").path("hits")).hasSize(2);
        assertThat(searchResult.path("hits").path("hits").get(1).path("_source").has("embedding"))
                .isFalse();
        assertThat(json(send(baseUrl, "GET", "/" + GENERATION + "/_doc/" + zeroLexicalId, null))
                .path("_version").asLong()).isEqualTo(1L);
        assertThat(json(send(baseUrl, "GET", "/" + GENERATION + "/_doc/" + zeroVectorId, null))
                .path("_version").asLong()).isEqualTo(2L);

        ObjectNode knnBody = objectMapper.createObjectNode();
        ObjectNode knnQuery = knnBody.put("size", 1)
                .putObject("query")
                .putObject("knn")
                .putObject("embedding");
        ArrayNode queryVector = knnQuery.putArray("vector");
        for (float value : floats()) {
            queryVector.add(value);
        }
        knnQuery.put("k", 1);
        HttpResponse<String> knn = send(baseUrl, "POST", "/" + GENERATION + "/_search",
                knnBody.toString());
        require2xx(knn);
        assertThat(objectMapper.readTree(knn.body()).path("hits").path("hits")).hasSize(1);

        client.promoteAliases("marketplace-listings-v1", GENERATION);
        assertThat(client.stableAliasState())
                .isEqualTo(new OpenSearchListingVectorBackfillClient.AliasState(
                        GENERATION, GENERATION));
        OpenSearchListingSearchClient publicSearch = publicSearchClient(baseUrl);
        PublicListingSearchCriteria criteria = new PublicListingSearchCriteria(
                "Desk",
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
                null);
        assertThat(publicSearch.searchIds("INDIVIDUAL", criteria, 10))
                .contains(zeroLexicalId, zeroVectorId);
        assertThat(publicSearch.searchIds("INDIVIDUAL", criteria, 10))
                .contains(zeroLexicalId, zeroVectorId);

        String liveId = "01L00000000000000000000523";
        client.upsertCandidate(GENERATION, document(liveId, 0, false));
        assertThat(client.resolveVectorTargets(Optional.empty()).generations())
                .containsExactly(GENERATION);
        client.upsertVectorTarget(GENERATION, document(liveId, 0, true));
        require2xx(send(baseUrl, "POST", "/" + GENERATION + "/_refresh", null));
        assertThat(json(send(baseUrl, "GET", "/" + GENERATION + "/_count", null))
                .path("count").asInt()).isEqualTo(3);
        assertThat(vectorCount(baseUrl, GENERATION, liveId)).isEqualTo(1);

        client.upsertCandidate(GENERATION, document(liveId, 1, false));
        client.upsertVectorTarget(GENERATION, document(liveId, 0, true));
        require2xx(send(baseUrl, "POST", "/" + GENERATION + "/_refresh", null));
        assertThat(vectorCount(baseUrl, GENERATION, liveId)).isZero();

        client.rollbackAliases(GENERATION, "marketplace-listings-v1");
        assertThat(client.stableAliasState())
                .isEqualTo(new OpenSearchListingVectorBackfillClient.AliasState(
                        "marketplace-listings-v1", "marketplace-listings-v1"));
        assertThat(json(send(baseUrl, "GET", "/" + GENERATION + "/_count", null))
                .path("count").asInt()).isEqualTo(3);
        assertThat(client.resolveVectorTargets(Optional.empty()).ready()).isFalse();
    }

    private OpenSearchListingSearchClient publicSearchClient(String baseUrl) {
        ListingSearchProperties search = new ListingSearchProperties(
                "opensearch",
                new ListingSearchProperties.OpenSearchProperties(
                        baseUrl,
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        true));
        return new OpenSearchListingSearchClient(
                search,
                objectMapper,
                new ListingSearchProjectionMetrics(new SimpleMeterRegistry()),
                http,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ListingVectorBackfillDocument document(String id, boolean withVector) {
        return document(id, 7, withVector);
    }

    private ListingVectorBackfillDocument document(
            String id,
            long version,
            boolean withVector) {
        return new ListingVectorBackfillDocument(
                new ListingSearchDocument(
                        id,
                        "INDIVIDUAL",
                        "01C00000000000000000000521",
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
                        null),
                version,
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
                withVector ? "ATTACHED" : "MISSING",
                withVector ? floats() : null);
    }

    private int vectorCount(String baseUrl, String generation, String listingId)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode filters = body.putObject("query")
                .putObject("bool")
                .putArray("filter");
        filters.addObject().putObject("term").put("listingId", listingId);
        filters.addObject().putObject("exists").put("field", "embedding");
        return json(send(baseUrl, "POST", "/" + generation + "/_count", body.toString()))
                .path("count")
                .asInt();
    }

    private float[] floats() {
        float[] values = new float[1536];
        Arrays.fill(values, 0.25f);
        return values;
    }

    private HttpResponse<String> send(String baseUrl, String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        require2xx(response);
        return objectMapper.readTree(response.body());
    }

    private void require2xx(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .withFailMessage("OpenSearch response status=%s body=%s", response.statusCode(), response.body())
                .isBetween(200, 299);
    }
}
