package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.internal-service-token=test-agent-token",
        "commerce.internal-service-token=test-commerce-token",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false"
})
@AutoConfigureMockMvc
class ListingKnowledgePublicationIntegrationTests {

    private static final String CATEGORY_ID = "01K00000000000000000000001";
    private static final String USER_ID = "01U00000000000000000000001";

    @ServiceConnection
    static MySQLContainer<?> mysqlContainer = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        mysqlContainer.start();
    }

    @Autowired
    ListingKnowledgePublicationService publicationService;

    @Autowired
    ListingKnowledgeRepository knowledgeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    AuthServiceClient authServiceClient;

    @MockBean
    ListingMediaStorage listingMediaStorage;

    @BeforeEach
    void clearPublicationRows() {
        jdbcTemplate.update("delete from outbox_events");
        jdbcTemplate.update("delete from listing_knowledge_versions");
    }

    @Test
    void activationStoresImmutablePublicSnapshotAndReferenceOnlyOutboxEvent() throws Exception {
        String listingId = listingId(101);
        Instant occurredAt = Instant.parse("2026-07-18T10:15:30Z");
        publicationService.reconcile(
                listing(listingId, 4, "PENDING_REVIEW", "PENDING", null),
                listing(listingId, 5, "ACTIVE", "APPROVED", occurredAt),
                occurredAt);

        ListingKnowledgeVersion source = knowledgeRepository.findExact(listingId, 5).orElseThrow();
        assertThat(source.lifecycle()).isEqualTo("ACTIVE");
        assertThat(source.supersedesVersion()).isNull();
        assertThat(source.title()).isEqualTo("Used bicycle");
        assertThat(source.description()).isEqualTo("Public listing description");
        assertThat(source.publicCity()).isEqualTo("Irvine");
        assertThat(source.publicRegion()).isEqualTo("CA");
        assertThat(source.priceAmount()).isEqualByComparingTo("250.0000");
        assertThat(source.contentHash())
                .isEqualTo("677fcb65310897a226ef006a9b51a0b9aa7de6e9e8bcec14576ce989eb260ef5");

        String payload = jdbcTemplate.queryForObject(
                "select payload_json from outbox_events where aggregate_id = ?",
                String.class,
                listingId);
        JsonNode payloadJson = objectMapper.readTree(payload);
        assertThat(payloadJson.path("listingId").asText()).isEqualTo(listingId);
        assertThat(payloadJson.path("listingVersion").asText()).isEqualTo("5");
        assertThat(payloadJson.path("knowledgeLifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(payloadJson.path("language").asText()).isEqualTo("und");
        assertThat(payloadJson.has("title")).isFalse();
        assertThat(payloadJson.has("description")).isFalse();
        assertThat(payloadJson.has("publicCity")).isFalse();
        assertThat(payloadJson.has("priceAmount")).isFalse();
        assertThat(payload).doesNotContain("seller", "contact", "address", "media", "moderation");
    }

    @Test
    void updateAndInvalidationCreateMonotonicVersionsAndExactTombstone() {
        String listingId = listingId(102);
        Instant activatedAt = Instant.parse("2026-07-18T10:00:00Z");
        ListingDraftResponse activeVersionOne = listing(listingId, 1, "ACTIVE", "APPROVED", activatedAt);
        publicationService.bootstrap(activeVersionOne, activatedAt);

        ListingDraftResponse activeVersionTwo = listing(listingId, 2, "ACTIVE", "APPROVED", activatedAt);
        publicationService.reconcile(activeVersionOne, activeVersionTwo, activatedAt.plusSeconds(60));
        ListingDraftResponse closedVersionThree = listing(listingId, 3, "CLOSED", "APPROVED", activatedAt);
        publicationService.reconcile(activeVersionTwo, closedVersionThree, activatedAt.plusSeconds(120));

        ListingKnowledgeVersion update = knowledgeRepository.findExact(listingId, 2).orElseThrow();
        ListingKnowledgeVersion tombstone = knowledgeRepository.findExact(listingId, 3).orElseThrow();
        assertThat(update.lifecycle()).isEqualTo("ACTIVE");
        assertThat(update.supersedesVersion()).isEqualTo(1);
        assertThat(tombstone.lifecycle()).isEqualTo("INVALIDATED");
        assertThat(tombstone.supersedesVersion()).isEqualTo(2);
        assertThat(tombstone.title()).isNull();
        assertThat(tombstone.contentHash()).isNull();
        assertThat(jdbcTemplate.queryForList(
                        "select event_type from outbox_events where aggregate_id = ? order by created_at",
                        String.class,
                        listingId))
                .containsExactly("listing.activated", "listing.updated", "listing.deactivated");
    }

    @Test
    void snapshotAndOutboxRollbackTogether() {
        String listingId = listingId(103);
        ListingDraftResponse pending = listing(listingId, 7, "PENDING_REVIEW", "PENDING", null);
        ListingDraftResponse active = listing(listingId, 8, "ACTIVE", "APPROVED", Instant.now());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            publicationService.reconcile(pending, active, Instant.now());
            throw new TestRollbackException();
        })).isInstanceOf(TestRollbackException.class);

        assertThat(knowledgeRepository.findExact(listingId, 8)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where aggregate_id = ?",
                Integer.class,
                listingId)).isZero();
    }

    @Test
    void exactSourceRequiresDistinctAgentTokenAndNeverFallsBack() throws Exception {
        String listingId = listingId(104);
        publicationService.bootstrap(
                listing(listingId, 11, "ACTIVE", "APPROVED", Instant.parse("2026-07-18T11:00:00Z")),
                Instant.parse("2026-07-18T11:00:00Z"));

        mockMvc.perform(get(
                        "/api/v1/internal/agent/knowledge/listings/{listingId}/versions/{sourceVersion}",
                        listingId,
                        11)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType", equalTo("LISTING")))
                .andExpect(jsonPath("$.sourceId", equalTo(listingId)))
                .andExpect(jsonPath("$.sourceVersion", equalTo("11")))
                .andExpect(jsonPath("$.content.title", equalTo("Used bicycle")))
                .andExpect(jsonPath("$.content.publicLocation.city", equalTo("Irvine")))
                .andExpect(jsonPath("$.content.price.currency", equalTo("USD")))
                .andExpect(jsonPath("$.sellerId").doesNotExist())
                .andExpect(jsonPath("$.exactLocation").doesNotExist());

        mockMvc.perform(get(
                        "/api/v1/internal/agent/knowledge/listings/{listingId}/versions/{sourceVersion}",
                        listingId,
                        11))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                        "/api/v1/internal/agent/knowledge/listings/{listingId}/versions/{sourceVersion}",
                        listingId,
                        11)
                        .header("X-Agent-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(
                        "/api/v1/internal/agent/knowledge/listings/{listingId}/versions/{sourceVersion}",
                        listingId,
                        12)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_KNOWLEDGE_SOURCE_NOT_FOUND")));
    }

    @Test
    void customerServiceContextRequiresAgentTokenAndOnlyReturnsCurrentEligibleListing() throws Exception {
        String listingId = listingId(140);
        Instant publishedAt = Instant.parse("2026-07-19T09:00:00Z");
        ListingDraftResponse active = listing(
                listingId,
                7,
                "ACTIVE",
                "APPROVED",
                publishedAt);
        publicationService.bootstrap(active, publishedAt);

        mockMvc.perform(get(
                        "/api/v1/internal/agent/listings/{listingId}/customer-service-context",
                        listingId)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(listingId)))
                .andExpect(jsonPath("$.sourceVersion", equalTo("7")))
                .andExpect(jsonPath("$.eligible", equalTo(true)))
                .andExpect(jsonPath("$.sellerType", equalTo("INDIVIDUAL")))
                .andExpect(jsonPath("$.title", equalTo("Used bicycle")))
                .andExpect(jsonPath("$.thumbnailUrl", nullValue()));

        mockMvc.perform(get(
                        "/api/v1/internal/agent/listings/{listingId}/customer-service-context",
                        listingId))
                .andExpect(status().isForbidden());

        publicationService.reconcile(
                active,
                listing(listingId, 8, "CLOSED", "APPROVED", publishedAt),
                publishedAt.plusSeconds(30));
        mockMvc.perform(get(
                        "/api/v1/internal/agent/listings/{listingId}/customer-service-context",
                        listingId)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void exportUsesStableCursorAndExcludesLatestTombstones() throws Exception {
        String firstId = listingId(105);
        String secondId = listingId(106);
        String removedId = listingId(107);
        Instant publishedAt = Instant.parse("2026-07-18T12:00:00Z");
        publicationService.bootstrap(listing(firstId, 1, "ACTIVE", "APPROVED", publishedAt), publishedAt);
        publicationService.bootstrap(listing(secondId, 1, "ACTIVE", "APPROVED", publishedAt), publishedAt);
        ListingDraftResponse removedActive = listing(removedId, 1, "ACTIVE", "APPROVED", publishedAt);
        publicationService.bootstrap(removedActive, publishedAt);
        publicationService.reconcile(
                removedActive,
                listing(removedId, 2, "CLOSED", "APPROVED", publishedAt),
                publishedAt.plusSeconds(1));

        String firstPage = mockMvc.perform(get("/api/v1/internal/agent/knowledge/listings/export")
                        .header("X-Agent-Internal-Service-Token", "test-agent-token")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourceId", equalTo(firstId)))
                .andExpect(jsonPath("$.hasMore", equalTo(true)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = objectMapper.readTree(firstPage).path("nextCursor").asText();

        mockMvc.perform(get("/api/v1/internal/agent/knowledge/listings/export")
                        .header("X-Agent-Internal-Service-Token", "test-agent-token")
                        .param("limit", "1")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sourceId", equalTo(secondId)))
                .andExpect(jsonPath("$.nextCursor", nullValue()))
                .andExpect(jsonPath("$.hasMore", equalTo(false)));
    }

    @Test
    void actualModerationApprovalPublishesInTheListingTransaction() throws Exception {
        String listingId = listingId(108);
        insertPendingListing(listingId, 4);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001",
                        "PLATFORM_ADMIN"));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/decision", listingId)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .header("If-Match", "4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "reason": "Public listing content approved"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingVersion", equalTo(5)));

        assertThat(knowledgeRepository.findExact(listingId, 5))
                .hasValueSatisfying(source -> assertThat(source.lifecycle()).isEqualTo("ACTIVE"));
        assertThat(jdbcTemplate.queryForObject(
                "select event_type from outbox_events where aggregate_id = ?",
                String.class,
                listingId)).isEqualTo("listing.activated");
    }

    @Test
    void actualAdminUpdateAndRemovalPublishNewVersionAndTombstone() throws Exception {
        String listingId = listingId(110);
        insertActiveListing(listingId, 0);
        when(authServiceClient.requirePlatformAdmin(anyString()))
                .thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                        "01A00000000000000000000001",
                        "PLATFORM_ADMIN"));

        mockMvc.perform(patch("/api/v1/admin/listings/{listingId}", listingId)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": "%s",
                                  "title": "Admin updated bicycle",
                                  "description": "Approved updated public description.",
                                  "condition": "GOOD",
                                  "conditionNotes": null,
                                  "price": {"amount": 225.00, "currency": "USD"},
                                  "negotiable": true,
                                  "location": {"city": "Irvine", "region": "CA"},
                                  "sku": null,
                                  "quantity": 1,
                                  "reason": "Corrected public listing facts"
                                }
                                """.formatted(CATEGORY_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", equalTo(1)));

        mockMvc.perform(post("/api/v1/admin/listings/{listingId}/remove", listingId)
                        .with(jwt().jwt(value -> value.tokenValue("admin-token")))
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Listing is no longer eligible"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", equalTo(2)));

        ListingKnowledgeVersion update = knowledgeRepository.findExact(listingId, 1).orElseThrow();
        ListingKnowledgeVersion tombstone = knowledgeRepository.findExact(listingId, 2).orElseThrow();
        assertThat(update.lifecycle()).isEqualTo("ACTIVE");
        assertThat(update.supersedesVersion()).isEqualTo(0);
        assertThat(update.title()).isEqualTo("Admin updated bicycle");
        assertThat(tombstone.lifecycle()).isEqualTo("INVALIDATED");
        assertThat(tombstone.supersedesVersion()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                        "select event_type from outbox_events where aggregate_id = ? order by created_at",
                        String.class,
                        listingId))
                .containsExactly("listing.updated", "listing.deactivated");
    }

    @Test
    void outboxClaimsAreExclusiveAndFailedClaimsBecomeRetryable() {
        String listingId = listingId(109);
        publicationService.bootstrap(
                listing(listingId, 1, "ACTIVE", "APPROVED", Instant.now()),
                Instant.now());
        Instant now = Instant.now().plusSeconds(1);

        List<ListingKnowledgeOutboxEvent> firstClaim =
                knowledgeRepository.claimBatch("01Q00000000000000000000001", now, now.plusSeconds(30), 10);
        List<ListingKnowledgeOutboxEvent> secondClaim =
                knowledgeRepository.claimBatch("01Q00000000000000000000002", now, now.plusSeconds(30), 10);

        assertThat(firstClaim).hasSize(1);
        assertThat(secondClaim).isEmpty();
        assertThat(knowledgeRepository.markFailed(
                firstClaim.getFirst().eventId(),
                "01Q00000000000000000000001",
                now.minusSeconds(1),
                "TEST_FAILURE")).isTrue();
        assertThat(knowledgeRepository.claimBatch(
                "01Q00000000000000000000003",
                now,
                now.plusSeconds(30),
                10)).hasSize(1);
    }

    private ListingDraftResponse listing(
            String listingId,
            long version,
            String status,
            String moderationStatus,
            Instant publishedAt) {
        Instant now = Instant.parse("2026-07-18T09:00:00Z").plusSeconds(version);
        return new ListingDraftResponse(
                listingId,
                "INDIVIDUAL",
                USER_ID,
                null,
                null,
                null,
                CATEGORY_ID,
                "Used bicycle",
                "Public listing description",
                "GOOD",
                null,
                new BigDecimal("250.0000"),
                "USD",
                true,
                null,
                1,
                "Irvine",
                "CA",
                status,
                moderationStatus,
                "ACTIVE".equals(status) ? "ADMIN_REVIEW" : null,
                publishedAt,
                version,
                now.minusSeconds(60),
                now,
                null,
                null,
                null,
                List.of());
    }

    private void insertPendingListing(String listingId, long version) {
        Instant now = Instant.parse("2026-07-18T08:00:00Z");
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city,
                    public_region, status, moderation_status, publication_source,
                    published_at, version, created_at, updated_at
                )
                values (?, 'INDIVIDUAL', ?, null, null, ?, ?, ?, 'GOOD', null,
                        250.0000, 'USD', true, null, 1, 'Irvine', 'CA',
                        'PENDING_REVIEW', 'PENDING', null, null, ?, ?, ?)
                """,
                listingId,
                USER_ID,
                CATEGORY_ID,
                "Used bicycle",
                "Public listing description",
                version,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private void insertActiveListing(String listingId, long version) {
        Instant now = Instant.parse("2026-07-18T08:00:00Z");
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city,
                    public_region, status, moderation_status, publication_source,
                    published_at, version, created_at, updated_at
                )
                values (?, 'INDIVIDUAL', ?, null, null, ?, ?, ?, 'GOOD', null,
                        250.0000, 'USD', true, null, 1, 'Irvine', 'CA',
                        'ACTIVE', 'APPROVED', 'ADMIN_REVIEW', ?, ?, ?, ?)
                """,
                listingId,
                USER_ID,
                CATEGORY_ID,
                "Used bicycle",
                "Public listing description",
                Timestamp.from(now),
                version,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now));
    }

    private String listingId(int value) {
        return "01L" + String.format("%023d", value);
    }

    private static final class TestRollbackException extends RuntimeException {
    }
}
