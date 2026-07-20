package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.internal-service-token=test-agent-token",
        "commerce.internal-service-token=test-commerce-token",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false",
        "category.guidance.publication.topic=category-guidance-v1"
})
@AutoConfigureMockMvc
class CategoryGuidanceIntegrationTests {

    private static final String CATEGORY_ID = "01K00000000000000000000002";
    private static final String ADMIN_ID = "01A00000000000000000000001";

    @ServiceConnection
    static MySQLContainer<?> mysqlContainer = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        mysqlContainer.start();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CategoryGuidanceRepository repository;

    @Autowired
    CategoryGuidanceService service;

    @Autowired
    CategoryGuidanceCanonicalHasher hasher;

    @Autowired
    CategoryGuidanceSourceService sourceService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockBean
    AuthServiceClient authServiceClient;

    @MockBean
    ListingMediaStorage listingMediaStorage;

    @MockBean
    CurrentActorProvider currentActorProvider;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from outbox_events");
        jdbcTemplate.update("delete from category_guidance_versions");
        jdbcTemplate.update("update categories set status = 'ACTIVE' where id = ?", CATEGORY_ID);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(ADMIN_ID, "PLATFORM_ADMIN"));
        when(currentActorProvider.currentActor())
                .thenReturn(new CurrentActor(
                        ADMIN_ID,
                        "admin-token",
                        "admin@example.test",
                        "Platform Admin",
                        true));
    }

    @Test
    void publishUpdateRetireAndReactivateAppendImmutableVersions() throws Exception {
        publish("0", "Buying used electronics", "Check the model and visible condition.")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.sourceType", equalTo("CATEGORY_GUIDANCE")))
                .andExpect(jsonPath("$.sourceVersion", equalTo("1")))
                .andExpect(jsonPath("$.content.categorySlug", equalTo("electronics")));

        publish("1", "Buying electronics safely", "Confirm the model and included accessories.")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.supersedesVersion", equalTo("1")));

        mockMvc.perform(post(
                        "/api/v1/admin/categories/{categoryId}/guidance/en/retire",
                        CATEGORY_ID)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.lifecycle", equalTo("INVALIDATED")))
                .andExpect(jsonPath("$.content").doesNotExist());

        publish("3", "Buying electronics again", "Inspect condition before arranging payment.")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""));

        assertThat(repository.findExact(CATEGORY_ID, "en", 1).orElseThrow().title())
                .isEqualTo("Buying used electronics");
        assertThat(jdbcTemplate.queryForList("""
                        select event_type
                        from outbox_events
                        where aggregate_id = ?
                        order by created_at, event_id
                        """, String.class, CATEGORY_ID))
                .containsExactly(
                        "category-guidance.activated",
                        "category-guidance.updated",
                        "category-guidance.invalidated",
                        "category-guidance.activated");
    }

    @Test
    void staleOrMissingVersionAndUnsafeTextAreRejected() throws Exception {
        publish("0", "Buying used electronics", "Check the model and visible condition.")
                .andExpect(status().isOk());
        publish("0", "Stale edit", "This must not be accepted.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("CATEGORY_GUIDANCE_VERSION_CONFLICT")));

        mockMvc.perform(post(
                        "/api/v1/admin/categories/{categoryId}/guidance/en/versions",
                        CATEGORY_ID)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Missing version","body":"No If-Match header."}
                                """))
                .andExpect(status().isConflict());

        publish("1", "<script>alert(1)</script>", "Unsafe markup.")
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(
                        "/api/v1/admin/categories/{categoryId}/guidance/en/versions",
                        CATEGORY_ID)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Unexpected field",
                                  "body":"Strict request contract.",
                                  "actorUserId":"01A99999999999999999999999"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_INVALID_REQUEST")));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from category_guidance_versions",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void inactiveCategoryCannotPublishAndNonAdminCannotUseAdminRoute() throws Exception {
        jdbcTemplate.update("update categories set status = 'INACTIVE' where id = ?", CATEGORY_ID);
        publish("0", "Inactive category", "This must not publish.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("CATEGORY_GUIDANCE_CATEGORY_INACTIVE")));

        mockMvc.perform(post(
                        "/api/v1/admin/categories/{categoryId}/guidance/en/versions",
                        CATEGORY_ID)
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Unauthorized","body":"No authenticated admin."}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exactAndExportRequireAgentTokenAndNeverFallBack() throws Exception {
        publish("0", "Buying used electronics", "Check the model and visible condition.")
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/v1/internal/agent/knowledge/category-guidance/{categoryId}/languages/en/versions/1",
                        CATEGORY_ID)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceVersion", equalTo("1")))
                .andExpect(jsonPath("$.content.categoryName", equalTo("Electronics")));
        mockMvc.perform(get(
                        "/api/v1/internal/agent/knowledge/category-guidance/{categoryId}/languages/en/versions/2",
                        CATEGORY_ID)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/internal/agent/knowledge/category-guidance/export")
                        .header("X-Agent-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/internal/agent/knowledge/category-guidance/export")
                        .header("X-Agent-Internal-Service-Token", "test-agent-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourceId", equalTo(CATEGORY_ID)));
    }

    @Test
    void exportCursorKeepsItsWatermarkAndNewExportsExcludeTombstonesAndInactiveCategories() {
        service.publish(
                CATEGORY_ID,
                "en",
                0,
                new CategoryGuidancePublishRequest("English guidance", "English body."));
        service.publish(
                CATEGORY_ID,
                "fr",
                0,
                new CategoryGuidancePublishRequest("Guide français", "Corps français."));

        CategoryGuidancePageResponse first =
                sourceService.export("test-agent-token", null, 1);
        assertThat(first.items()).singleElement()
                .satisfies(item -> assertThat(item.language()).isEqualTo("en"));
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();

        service.retire(CATEGORY_ID, "fr", 1);
        service.publish(
                CATEGORY_ID,
                "de",
                0,
                new CategoryGuidancePublishRequest("Deutscher Leitfaden", "Deutscher Text."));

        CategoryGuidancePageResponse second =
                sourceService.export("test-agent-token", first.nextCursor(), 1);
        assertThat(second.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.language()).isEqualTo("fr");
                    assertThat(item.lifecycle()).isEqualTo("ACTIVE");
                });
        assertThat(second.hasMore()).isFalse();

        CategoryGuidancePageResponse current =
                sourceService.export("test-agent-token", null, 100);
        assertThat(current.items())
                .extracting(CategoryGuidanceSourceResponse::language)
                .containsExactly("de", "en");

        jdbcTemplate.update("update categories set status = 'INACTIVE' where id = ?", CATEGORY_ID);
        assertThat(sourceService.export("test-agent-token", null, 100).items()).isEmpty();
    }

    @Test
    void sourceAndOutboxRollbackTogetherAndDeactivationInvalidatesEveryLanguage() throws Exception {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.publish(
                    CATEGORY_ID,
                    "en",
                    0,
                    new CategoryGuidancePublishRequest("Rollback", "Rollback body."));
            throw new TestRollbackException();
        })).isInstanceOf(TestRollbackException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from category_guidance_versions",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox_events",
                Integer.class)).isZero();

        publish("0", "English guidance", "English body.").andExpect(status().isOk());
        mockMvc.perform(post(
                        "/api/v1/admin/categories/{categoryId}/guidance/fr/versions",
                        CATEGORY_ID)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Guide français","body":"Vérifiez le modèle et l'état."}
                                """))
                .andExpect(status().isOk());

        int invalidated = service.invalidateForCategoryDeactivation(
                CATEGORY_ID,
                ADMIN_ID,
                "test-correlation",
                Instant.parse("2026-07-19T08:00:00Z"));
        assertThat(invalidated).isEqualTo(2);
        assertThat(repository.findLatest(CATEGORY_ID, "en").orElseThrow().lifecycle())
                .isEqualTo("INVALIDATED");
        assertThat(repository.findLatest(CATEGORY_ID, "fr").orElseThrow().sourceVersion())
                .isEqualTo(2);
    }

    @Test
    void canonicalHashMatchesCrossLanguageFixtureAndEventIsReferenceOnly() throws Exception {
        assertThat(hasher.hash(
                CATEGORY_ID,
                "electronics",
                "Electronics",
                "en",
                "Buying used electronics",
                "Check the model and visible condition."))
                .isEqualTo("39045412a4f44cd2d72eb803c7b4e65ac1ca9d0c205c58629b0ada8b77b6eee9");

        publish("0", "Buying used electronics", "Check the model and visible condition.")
                .andExpect(status().isOk());
        String payload = jdbcTemplate.queryForObject(
                "select payload_json from outbox_events where aggregate_id = ?",
                String.class,
                CATEGORY_ID);
        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.path("sourceType").asText()).isEqualTo("CATEGORY_GUIDANCE");
        assertThat(json.path("sourceVersion").asText()).isEqualTo("1");
        assertThat(payload).doesNotContain(
                "Buying used electronics",
                "visible condition",
                "actorUserId",
                "categoryName");
        assertThat(jdbcTemplate.queryForObject(
                "select message_key from outbox_events where aggregate_id = ?",
                String.class,
                CATEGORY_ID)).isEqualTo(CATEGORY_ID + ":en");
    }

    private org.springframework.test.web.servlet.ResultActions publish(
            String ifMatch,
            String title,
            String body) throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/admin/categories/{categoryId}/guidance/en/versions",
                        CATEGORY_ID)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CategoryGuidancePublishRequest(title, body))));
    }

    private static class TestRollbackException extends RuntimeException {
    }
}
