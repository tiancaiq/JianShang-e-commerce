package com.msb.ecom.product_service.search.hybrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.search.ListingSearchDocument;
import com.msb.ecom.product_service.search.ListingSearchProjectionMetrics;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.ListingVectorBackfillDocument;
import com.msb.ecom.product_service.search.ListingVectorBackfillProperties;
import com.msb.ecom.product_service.search.OpenSearchListingVectorBackfillClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OpenSearchListingHybridSearchIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Container
    static GenericContainer<?> openSearch = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.15.0"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .waitingFor(Wait.forHttp("/_cluster/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void realOpenSearchRunsBm25AndFilteredKnnOnlyThroughPromotedV2ReadAlias()
            throws Exception {
        String base = "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200);
        send(base, "PUT", "/marketplace-listings-v1", """
                {"settings":{"index.number_of_shards":1,"index.number_of_replicas":0},
                 "aliases":{"marketplace-listings":{},
                            "marketplace-listings-write":{"is_write_index":true}}}
                """);
        ListingSearchProperties search = properties(base);
        ListingVectorBackfillProperties backfillProperties =
                new ListingVectorBackfillProperties(
                        true, "marketplace-listings-v2", 25, 100, 5_000_000, true);
        OpenSearchListingVectorBackfillClient backfill =
                new OpenSearchListingVectorBackfillClient(
                        search,
                        backfillProperties,
                        new ObjectMapper(),
                        new ListingSearchProjectionMetrics(new SimpleMeterRegistry()));
        String generation = backfill.allocateGenerationName();
        backfill.createInactiveGeneration(generation);
        backfill.backfillRegisteredGeneration(generation, List.of(
                document("81D00000000000000000000101", "Desk chair", vector(1.0f, 0.0f)),
                document("Z1D00000000000000000000102", "Sleep pillow", vector(0.0f, 1.0f)),
                document("01D00000000000000000000103", "Desk mat", null)));
        backfill.promoteAliases("marketplace-listings-v1", generation);

        OpenSearchListingHybridSearchClient hybrid =
                new OpenSearchListingHybridSearchClient(
                        search, backfillProperties, new ObjectMapper());
        ListingHybridSearchRequest request = new ListingHybridSearchRequest(
                "desk",
                vector(1.0f, 0.0f),
                new ListingHybridSearchRequest.Filters(
                        "01D00000000000000000000999",
                        "GOOD",
                        BigDecimal.TEN,
                        new BigDecimal("100.00"),
                        "USD",
                        "Irvine",
                        "Orange County"),
                20);

        String target = hybrid.validateReadAlias();
        List<String> lexical = hybrid.searchLexical(target, request);
        List<String> vector = hybrid.searchVector(target, request);

        assertThat(lexical)
                .contains("81D00000000000000000000101",
                        "01D00000000000000000000103")
                .doesNotContain("Z1D00000000000000000000102");
        assertThat(vector)
                .startsWith("81D00000000000000000000101")
                .contains("Z1D00000000000000000000102")
                .doesNotContain("01D00000000000000000000103");

        ListingHybridSearchRequest vectorOnlyRequest = new ListingHybridSearchRequest(
                "zzzzqqq",
                vector(0.0f, 1.0f),
                request.filters(),
                20);
        assertThat(hybrid.searchLexical(target, vectorOnlyRequest)).isEmpty();
        assertThat(hybrid.searchVector(target, vectorOnlyRequest))
                .startsWith("Z1D00000000000000000000102")
                .contains("81D00000000000000000000101")
                .doesNotContain("01D00000000000000000000103");
    }

    private ListingSearchProperties properties(String base) {
        return new ListingSearchProperties(
                "opensearch",
                new ListingSearchProperties.OpenSearchProperties(
                        base,
                        "marketplace-listings",
                        "marketplace-listings-write",
                        "marketplace-listings-v1",
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        false));
    }

    private ListingVectorBackfillDocument document(
            String id,
            String title,
            float[] embedding) {
        return new ListingVectorBackfillDocument(
                new ListingSearchDocument(
                        id,
                        "INDIVIDUAL",
                        "01D00000000000000000000999",
                        "office",
                        "Office",
                        title,
                        "Safe public description",
                        title + " Safe public description Office office GOOD Irvine Orange County",
                        "GOOD",
                        new BigDecimal("55.00"),
                        "USD",
                        "Irvine",
                        "irvine",
                        "Orange County",
                        "orange county",
                        NOW,
                        true,
                        null),
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
                embedding == null ? "MISSING" : "ATTACHED",
                embedding);
    }

    private float[] vector(float first, float second) {
        float[] vector = new float[1536];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private void send(String base, String method, String path, String body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .withFailMessage("status=%s body=%s", response.statusCode(), response.body())
                .isBetween(200, 299);
    }
}
