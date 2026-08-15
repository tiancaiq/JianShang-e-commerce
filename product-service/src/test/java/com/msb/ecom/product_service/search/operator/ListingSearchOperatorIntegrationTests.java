package com.msb.ecom.product_service.search.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.service.AuthServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "listing.search.engine=opensearch",
        "listing.search.opensearch.initialize-index=false",
        "listing.search.projection-sync.enabled=true",
        "listing.search.vector-backfill.enabled=true",
        "listing.search.promotion.rebuild-enabled=true",
        "listing.search.promotion.promotion-enabled=true",
        "listing.search.operator.commands-enabled=true",
        "listing.search.operator.status-enabled=true",
        "listing.search.vector-sync.enabled=false",
        "listing.search.vector-sync.worker-enabled=false",
        "listing.search.hybrid.enabled=false",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ListingSearchOperatorIntegrationTests {
    private static final String ADMIN_ID = "01A00000000000000000000811";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @Container
    static GenericContainer<?> openSearch = new GenericContainer<>(
            DockerImageName.parse("opensearchproject/opensearch:2.15.0"))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");

    @DynamicPropertySource
    static void openSearchProperties(DynamicPropertyRegistry registry) {
        registry.add("listing.search.opensearch.base-url", () ->
                "http://" + openSearch.getHost() + ":" + openSearch.getMappedPort(9200));
        registry.add("listing.search.opensearch.request-timeout", () -> "PT10S");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean CurrentActorProvider currentActorProvider;
    @MockBean AuthServiceClient authServiceClient;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @BeforeEach
    void reset() throws Exception {
        when(currentActorProvider.currentActor()).thenReturn(
                new CurrentActor("subject", "admin-token", null, null, false));
        when(authServiceClient.requirePlatformAdmin("admin-token")).thenReturn(
                new AuthServiceClient.PlatformAdminAuthorization(ADMIN_ID, "PLATFORM_ADMIN"));
        jdbcTemplate.update("delete from listing_search_operator_audit");
        jdbcTemplate.update("delete from listing_search_rebuild_runs");
        jdbcTemplate.update("delete from listing_search_projection_work");
        send("DELETE", "/_all", null);
        require2xx(send("PUT", "/marketplace-listings-v1", """
                {"settings":{"index.number_of_shards":1,"index.number_of_replicas":0},
                 "aliases":{"marketplace-listings":{},
                            "marketplace-listings-write":{"is_write_index":true}}}
                """));
    }

    @Test
    void authenticatedOperatorPreparesObservesCatchesUpPromotesAndRecovers()
            throws Exception {
        assertThat(jdbcTemplate.queryForObject(
                "select version from listings where id = ?",
                Long.class,
                "01D00000000000000000000101"))
                .isZero();
        String preparedJson = mvc.perform(post(
                        "/api/v1/admin/search/listings/vector-rebuilds")
                        .with(jwt())
                        .header("X-Correlation-Id", "operator-e2e-prepare"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CATCHING_UP"))
                .andExpect(jsonPath("$.candidateRole").value("INACTIVE_V2_CANDIDATE"))
                .andExpect(jsonPath("$.canCatchUp").value(true))
                .andExpect(jsonPath("$.canPromote").value(true))
                .andExpect(jsonPath("$.canRecover").value(false))
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(preparedJson).path("runId").asText();

        mvc.perform(get("/api/v1/admin/search/listings/vector-rebuilds/{runId}", runId)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandOutcome").value("OBSERVED"))
                .andExpect(jsonPath("$.canPromote").value(true));
        mvc.perform(post(
                        "/api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up",
                        runId).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CATCHING_UP"));
        mvc.perform(post(
                        "/api/v1/admin/search/listings/vector-rebuilds/{runId}/promote",
                        runId).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PROMOTED"))
                .andExpect(jsonPath("$.candidateRole").value("ACTIVE_V2"))
                .andExpect(jsonPath("$.canCatchUp").value(false))
                .andExpect(jsonPath("$.canPromote").value(false))
                .andExpect(jsonPath("$.canRecover").value(false));
        mvc.perform(post(
                        "/api/v1/admin/search/listings/vector-rebuilds/{runId}/recover",
                        runId).with(jwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(
                        "VECTOR_REBUILD_STATE_CONFLICT"));

        JsonNode readAliases = json(send("GET", "/_alias/marketplace-listings", null));
        JsonNode writeAliases = json(send("GET", "/_alias/marketplace-listings-write", null));
        assertThat(readAliases.size()).isEqualTo(1);
        assertThat(writeAliases.size()).isEqualTo(1);
        String promoted = readAliases.fieldNames().next();
        assertThat(promoted).startsWith("marketplace-listings-v2-g");
        assertThat(writeAliases.has(promoted)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select version from listings where id = ?",
                Long.class,
                "01D00000000000000000000101"))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_search_operator_audit", Long.class))
                .isEqualTo(4L);
        assertThat(jdbcTemplate.queryForList("""
                select action, outcome from listing_search_operator_audit
                order by occurred_at, audit_id
                """))
                .allSatisfy(row -> assertThat(row.keySet())
                        .containsExactlyInAnyOrder("action", "outcome"));
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        String base = "http://" + openSearch.getHost() + ":"
                + openSearch.getMappedPort(9200);
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        require2xx(response);
        return objectMapper.readTree(response.body());
    }

    private void require2xx(HttpResponse<String> response) {
        assertThat(response.statusCode())
                .withFailMessage("Unexpected OpenSearch status %s", response.statusCode())
                .isBetween(200, 299);
    }
}
