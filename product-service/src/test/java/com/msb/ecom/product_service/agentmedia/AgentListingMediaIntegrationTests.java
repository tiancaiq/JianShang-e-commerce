package com.msb.ecom.product_service.agentmedia;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.internal-service-token=test-agent-token",
        "agent.listing-media-tool-enabled=true",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false"
})
@AutoConfigureMockMvc
class AgentListingMediaIntegrationTests {

    private static final String CATEGORY_ID = "01ARZ3NDEKTSV4RRFFQ69G5FA0";
    private static final String ACTOR_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA";
    private static final String OTHER_ACTOR_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String MEDIA_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE";
    private static final String OBJECT_KEY = "agent-owned-draft/test.png";
    private static final byte[] PNG =
            "\u0089PNG\r\n\u001a\nintegration-image".getBytes(StandardCharsets.ISO_8859_1);

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

    @MockBean
    ListingMediaStorage storage;

    @BeforeEach
    void resetRows() {
        jdbcTemplate.update("delete from listing_media_objects where id = ?", MEDIA_ID);
        jdbcTemplate.update("delete from listings where id = ?", LISTING_ID);
        jdbcTemplate.update("delete from categories where id = ?", CATEGORY_ID);
        jdbcTemplate.update("""
                        insert into categories (
                            id, parent_id, slug, name, status, display_order, created_at, updated_at
                        ) values (?, null, ?, 'Agent media test', 'ACTIVE', 999, ?, ?)
                        """,
                CATEGORY_ID,
                "agent-media-test-" + System.nanoTime(),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
    }

    @Test
    void productDatabaseOwnershipAndByteVerificationReachTheStrictRoute() throws Exception {
        insertListing(ACTOR_ID, "DRAFT");
        insertMedia(ACTOR_ID, "UPLOADED", "NOT_SUBMITTED", "image/png", sha256(PNG));
        when(storage.readObject(OBJECT_KEY)).thenAnswer(invocation -> {
            org.assertj.core.api.Assertions.assertThat(
                    TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return PNG;
        });

        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token")
                        .header("X-Correlation-Id", "corr-integration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(ACTOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion", equalTo("ai-list-owned-draft-media-v1")))
                .andExpect(jsonPath("$.listingId", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.listingVersion", equalTo("12")))
                .andExpect(jsonPath("$.eligibility", equalTo("OWNED_DRAFT")))
                .andExpect(jsonPath("$.media[0].mediaId", equalTo(MEDIA_ID)))
                .andExpect(jsonPath("$.media[0].contentType", equalTo("image/png")))
                .andExpect(jsonPath("$.media[0].sha256", equalTo(sha256(PNG))))
                .andExpect(jsonPath("$.media[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$.actorUserId").doesNotExist())
                .andExpect(jsonPath("$.seller").doesNotExist());
    }

    @Test
    void crossActorAndCrossListingStyleReadsAreHiddenBeforeStorage() throws Exception {
        insertListing(OTHER_ACTOR_ID, "DRAFT");
        insertMedia(OTHER_ACTOR_ID, "UPLOADED", "NOT_SUBMITTED", "image/png", sha256(PNG));

        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(ACTOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("AGENT_LISTING_MEDIA_NOT_FOUND")));

        verify(storage, never()).readObject(OBJECT_KEY);
    }

    @Test
    void invalidTokenAndNotReadyOrRejectedStateNeverReadBytes() throws Exception {
        insertListing(ACTOR_ID, "DRAFT");
        insertMedia(ACTOR_ID, "PENDING_UPLOAD", "REJECTED", "image/png", sha256(PNG));

        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(ACTOR_ID)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "test-agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(ACTOR_ID)))
                .andExpect(status().isNotFound());

        verify(storage, never()).readObject(OBJECT_KEY);
    }

    private void insertListing(String ownerUserId, String status) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                        insert into listings (
                            id, seller_type, individual_seller_user_id, business_id, store_id,
                            category_id, title, description, condition_code, condition_notes,
                            price_amount, currency, negotiable, sku, quantity,
                            public_city, public_region, status, moderation_status,
                            version, created_at, updated_at
                        ) values (
                            ?, 'INDIVIDUAL', ?, null, null,
                            ?, 'Draft', 'Draft description', 'GOOD', null,
                            1.0000, 'USD', true, null, 1,
                            'Irvine', 'CA', ?, 'NOT_SUBMITTED',
                            12, ?, ?
                        )
                        """,
                LISTING_ID,
                ownerUserId,
                CATEGORY_ID,
                status,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private void insertMedia(
            String ownerUserId,
            String uploadStatus,
            String moderationStatus,
            String contentType,
            String checksum) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                        insert into listing_media_objects (
                            id, listing_id, seller_type, individual_seller_user_id, business_id,
                            object_bucket, object_key, original_file_name, content_type, size_bytes,
                            checksum_sha256, upload_status, moderation_status, version, created_at, updated_at
                        ) values (
                            ?, ?, 'INDIVIDUAL', ?, null,
                            'private-test', ?, 'test.png', ?, ?,
                            ?, ?, ?, 7, ?, ?
                        )
                        """,
                MEDIA_ID,
                LISTING_ID,
                ownerUserId,
                OBJECT_KEY,
                contentType,
                PNG.length,
                checksum,
                uploadStatus,
                moderationStatus,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    private String request(String actorUserId) {
        return """
                {
                  "schemaVersion": "ai-list-owned-draft-media-v1",
                  "actorUserId": "%s",
                  "mediaIds": ["%s"]
                }
                """.formatted(actorUserId, MEDIA_ID);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
