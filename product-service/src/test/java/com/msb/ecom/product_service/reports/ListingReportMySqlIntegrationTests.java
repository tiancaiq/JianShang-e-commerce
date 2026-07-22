package com.msb.ecom.product_service.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agent.internal-service-token=test-agent-token",
        "commerce.internal-service-token=test-commerce-token",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false",
        "listing-reports.intake-enabled=true",
        "listing-reports.retention-policy-approved=true",
        "listing-reports.abuse-hmac-secret=test-listing-report-hmac-secret-0000000001"
})
@AutoConfigureMockMvc
class ListingReportMySqlIntegrationTests {

    private static final String CATEGORY_ID = "01K00000000000000000000001";
    private static final String LISTING_ID = "01R00000000000000000000001";
    private static final String OWNER_ID = "01U00000000000000000000091";
    private static final String REPORTER_ID = "01U00000000000000000000092";
    private static final String OTHER_REPORTER_ID = "01U00000000000000000000093";
    private static final String BUSINESS_ID = "01B00000000000000000000091";
    private static final String STORE_ID = "01S00000000000000000000091";
    private static final String IMAGE_ID = "01I00000000000000000000091";
    private static final String MEDIA_ID = "01O00000000000000000000091";
    private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");

    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        mysql.start();
    }

    @Autowired ListingReportService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AuthServiceClient authServiceClient;
    @MockBean CurrentActorProvider currentActorProvider;
    @MockBean ListingReportTimeSource timeSource;
    @MockBean ListingMediaStorage listingMediaStorage;

    private final AtomicReference<String> actorUserId = new AtomicReference<>(REPORTER_ID);

    @BeforeEach
    void setUp() {
        clearReportData();
        deleteListingFixtures();
        seedPublicIndividualListing(LISTING_ID, OWNER_ID, IMAGE_ID, MEDIA_ID);
        actorUserId.set(REPORTER_ID);
        reset(authServiceClient, currentActorProvider, timeSource);
        when(timeSource.now()).thenReturn(NOW);
        when(currentActorProvider.currentActor()).thenAnswer(invocation -> new CurrentActor(
                actorUserId.get(),
                "token-" + actorUserId.get(),
                null,
                null,
                true));
        when(authServiceClient.requireCurrentUserForReport(anyString())).thenAnswer(invocation ->
                new AuthServiceClient.CurrentUser(actorUserId.get(), "ACTIVE"));
        when(authServiceClient.checkBusinessListingPermission(anyString(), anyString()))
                .thenReturn(AuthServiceClient.BusinessListingPermissionDecision.NOT_EDITABLE);
    }

    @Test
    void apiCreatesImmutableSnapshotEvidenceHistoryCaseAndSafeOutbox() throws Exception {
        mockMvc.perform(post("/api/v1/reports")
                        .with(jwt())
                        .header("Idempotency-Key", "report-intake-key-0001")
                        .header("X-Correlation-Id", "corr-rep-01a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("FRAUD_OR_MISREPRESENTATION", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("RECEIVED")))
                .andExpect(jsonPath("$.listingId", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.reporterUserId").doesNotExist())
                .andExpect(jsonPath("$.statement").doesNotExist())
                .andExpect(jsonPath("$.moderationCaseId").doesNotExist());

        assertThat(count("listing_reports")).isEqualTo(1);
        assertThat(count("listing_report_subject_snapshots")).isEqualTo(1);
        assertThat(count("listing_report_subject_media_snapshots")).isEqualTo(1);
        assertThat(count("listing_report_evidence")).isEqualTo(2);
        assertThat(count("listing_report_history")).isEqualTo(1);
        assertThat(countWhere("moderation_cases", "case_type = 'LISTING_REPORT'"))
                .isEqualTo(1);
        assertThat(countWhere("outbox_events", "event_type = 'listing-report.received.v1'"))
                .isEqualTo(1);

        String snapshotHash = jdbcTemplate.queryForObject(
                "select content_hash from listing_report_subject_snapshots",
                String.class);
        assertThat(snapshotHash).matches("[0-9a-f]{64}");
        assertThat(jdbcTemplate.queryForObject(
                "select snapshot_hash from listing_report_subject_media_snapshots",
                String.class)).matches("[0-9a-f]{64}");

        String payload = jdbcTemplate.queryForObject(
                "select payload_json from outbox_events where event_type = 'listing-report.received.v1'",
                String.class);
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.path("listingId").asText()).isEqualTo(LISTING_ID);
        assertThat(event.path("listingVersion").asLong()).isEqualTo(7);
        assertThat(event.path("policyVersion").asText()).isEqualTo("REP-00-V1");
        assertThat(payload).doesNotContain(
                REPORTER_ID,
                OWNER_ID,
                "description appears inconsistent",
                "statement",
                "reporter",
                "seller",
                "evidence");

        Instant created = jdbcTemplate.queryForObject(
                "select created_at from listing_reports",
                Timestamp.class).toInstant();
        Instant contentExpiry = jdbcTemplate.queryForObject(
                "select content_expires_at from listing_reports",
                Timestamp.class).toInstant();
        Instant metadataExpiry = jdbcTemplate.queryForObject(
                "select metadata_expires_at from listing_reports",
                Timestamp.class).toInstant();
        assertThat(contentExpiry).isEqualTo(created.plus(180, ChronoUnit.DAYS));
        assertThat(metadataExpiry).isEqualTo(created.plus(730, ChronoUnit.DAYS));
        assertThat(jdbcTemplate.queryForObject(
                "select legal_review_required from listing_reports", Boolean.class)).isTrue();
    }

    @Test
    void apiRequiresAnAuthenticatedActor() throws Exception {
        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-auth-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("DUPLICATE_OR_SPAM", false)))
                .andExpect(status().isUnauthorized());

        assertThat(count("listing_reports")).isZero();
    }

    @Test
    void exactReplayConflictAndSemanticDuplicateAreDatabaseEnforced() {
        ListingReportService.CreateResult created = service.create(
                request("FRAUD_OR_MISREPRESENTATION", true),
                "report-replay-key-0001",
                "corr-replay");
        ListingReportService.CreateResult replay = service.create(
                request("FRAUD_OR_MISREPRESENTATION", true),
                "report-replay-key-0001",
                "corr-replay");
        ListingReportService.CreateResult semanticDuplicate = service.create(
                request("FRAUD_OR_MISREPRESENTATION", true),
                "report-replay-key-0002",
                "corr-duplicate");

        assertThat(created.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(semanticDuplicate.created()).isFalse();
        assertThat(Set.of(
                created.response().reportId(),
                replay.response().reportId(),
                semanticDuplicate.response().reportId())).hasSize(1);
        assertThat(count("listing_reports")).isEqualTo(1);
        assertThat(countWhere("outbox_events", "event_type = 'listing-report.received.v1'"))
                .isEqualTo(1);

        ListingReportCreateRequest changed = request("FRAUD_OR_MISREPRESENTATION", false);
        changed.setStatement("A different allegation without contact data.");
        assertThatThrownBy(() -> service.create(changed, "report-replay-key-0001", "corr-conflict"))
                .isInstanceOf(ListingReportIdempotencyConflictException.class);
        assertThat(count("listing_reports")).isEqualTo(1);
    }

    @Test
    void ownerNonPublicMediaAndCrossActorAccessAreHidden() throws Exception {
        actorUserId.set(OWNER_ID);
        assertThatThrownBy(() -> service.create(
                request("DUPLICATE_OR_SPAM", false),
                "report-owner-key-0001",
                "corr-owner"))
                .isInstanceOf(ListingReportNotFoundException.class);
        assertThat(count("listing_reports")).isZero();

        actorUserId.set(REPORTER_ID);
        jdbcTemplate.update("update listings set status = 'PAUSED' where id = ?", LISTING_ID);
        assertThatThrownBy(() -> service.create(
                request("DUPLICATE_OR_SPAM", false),
                "report-hidden-key-0001",
                "corr-hidden"))
                .isInstanceOf(ListingReportNotFoundException.class);
        jdbcTemplate.update("update listings set status = 'ACTIVE' where id = ?", LISTING_ID);

        ListingReportCreateRequest invalidMedia = request("DUPLICATE_OR_SPAM", false);
        invalidMedia.setListingMediaIds(List.of("01I00000000000000000000099"));
        assertThatThrownBy(() -> service.create(
                invalidMedia,
                "report-media-key-0001",
                "corr-media"))
                .isInstanceOf(ListingReportNotFoundException.class);

        ListingReportService.CreateResult created = service.create(
                request("DUPLICATE_OR_SPAM", false),
                "report-read-key-0001",
                "corr-read");
        actorUserId.set(OTHER_REPORTER_ID);
        mockMvc.perform(get("/api/v1/reports/{reportId}", created.response().reportId()).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_REPORT_NOT_FOUND")));
    }

    @Test
    void businessEditableMemberIsHiddenAndAuthoritativeNonMemberMayReport() {
        replaceWithPublicBusinessListing();
        when(authServiceClient.checkBusinessListingPermission(anyString(), anyString()))
                .thenReturn(AuthServiceClient.BusinessListingPermissionDecision.EDITABLE);

        assertThatThrownBy(() -> service.create(
                request("DUPLICATE_OR_SPAM", false),
                "report-business-owner-key-0001",
                "corr-business-owner"))
                .isInstanceOf(ListingReportNotFoundException.class);
        assertNoReportPersistence();

        when(authServiceClient.checkBusinessListingPermission(anyString(), anyString()))
                .thenReturn(AuthServiceClient.BusinessListingPermissionDecision.NOT_EDITABLE);
        ListingReportService.CreateResult created = service.create(
                request("DUPLICATE_OR_SPAM", false),
                "report-business-nonmember-key-0001",
                "corr-business-nonmember");

        assertThat(created.created()).isTrue();
        assertThat(count("listing_reports")).isEqualTo(1);
        assertThat(countWhere("outbox_events", "event_type = 'listing-report.received.v1'"))
                .isEqualTo(1);
    }

    @Test
    void businessAuthFailuresRollBackAllReportState() {
        replaceWithPublicBusinessListing();

        assertBusinessAuthFailureLeavesNoState(new AuthServiceClient.AuthenticationException());
        assertBusinessAuthFailureLeavesNoState(
                new ListingAuthorizationException("Business report eligibility could not be authorized."));
        assertBusinessAuthFailureLeavesNoState(new AuthServiceClient.DependencyUnavailableException());
    }

    @Test
    void currentUserAuthFailuresCreateNoReportState() {
        assertCurrentUserFailureLeavesNoState(new AuthServiceClient.AuthenticationException());
        assertCurrentUserFailureLeavesNoState(
                new ListingAuthorizationException("Authenticated report access is required."));
        assertCurrentUserFailureLeavesNoState(new AuthServiceClient.DependencyUnavailableException());
    }

    @Test
    void hourlyLimitCountsAcceptedReportsAndReturnsBoundedRetry() {
        List<String> reasons = List.of(
                "PROHIBITED_OR_REGULATED_ITEM",
                "DANGEROUS_OR_UNSAFE_ITEM",
                "FRAUD_OR_MISREPRESENTATION",
                "COUNTERFEIT_OR_IP_CONCERN",
                "HATE_HARASSMENT_OR_THREAT");
        for (int index = 0; index < reasons.size(); index++) {
            service.create(
                    request(reasons.get(index), false),
                    "report-rate-key-000" + index,
                    "corr-rate");
        }

        assertThatThrownBy(() -> service.create(
                request("SEXUAL_OR_EXPLOITATIVE_CONTENT", false),
                "report-rate-key-0005",
                "corr-rate"))
                .isInstanceOfSatisfying(ListingReportRateLimitException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isBetween(1L, 3600L));
        assertThat(count("listing_reports")).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("""
                select accepted_count from listing_report_rate_limit_buckets
                where bucket_type = 'HOUR'
                """, Integer.class)).isEqualTo(5);
    }

    @Test
    void reportCaseAggregationAndRollbackDoNotChangeListingReviewLifecycle() {
        insertListingReviewCase();
        ListingReportService.CreateResult first = service.create(
                request("DUPLICATE_OR_SPAM", false),
                "report-case-key-0001",
                "corr-case");
        ListingReportService.CreateResult second = service.create(
                request("FRAUD_OR_MISREPRESENTATION", false),
                "report-case-key-0002",
                "corr-case");

        assertThat(first.response().reportId()).isNotEqualTo(second.response().reportId());
        assertThat(countWhere(
                "moderation_cases",
                "case_type = 'LISTING_REPORT' and routing_queue = 'MARKETPLACE_INTEGRITY'"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select status from moderation_cases where case_type = 'LISTING_REVIEW'
                """, String.class)).isEqualTo("OPEN");

        int beforeReports = count("listing_reports");
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.create(
                    request("PRIVACY_OR_PERSONAL_DATA", false),
                    "report-rollback-key-0001",
                    "corr-rollback");
            throw new TestRollbackException();
        })).isInstanceOf(TestRollbackException.class);
        assertThat(count("listing_reports")).isEqualTo(beforeReports);
        assertThat(countWhere(
                "outbox_events",
                "event_type = 'listing-report.received.v1'"))
                .isEqualTo(beforeReports);
    }

    @Test
    void concurrentDistinctKeysProduceOneSemanticReportAndOneOutboxEvent() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ListingReportService.CreateResult> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.create(
                        request("OTHER_POLICY_CONCERN", false),
                        "report-concurrent-key-0001",
                        "corr-concurrent");
            });
            Future<ListingReportService.CreateResult> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.create(
                        request("OTHER_POLICY_CONCERN", false),
                        "report-concurrent-key-0002",
                        "corr-concurrent");
            });
            start.countDown();

            ListingReportService.CreateResult firstResult = first.get(20, TimeUnit.SECONDS);
            ListingReportService.CreateResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.response().reportId()).isEqualTo(secondResult.response().reportId());
            assertThat(count("listing_reports")).isEqualTo(1);
            assertThat(countWhere("outbox_events", "event_type = 'listing-report.received.v1'"))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyProducesOneReportAndOneOutboxEvent() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ListingReportService.CreateResult> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.create(
                        request("OTHER_POLICY_CONCERN", false),
                        "report-same-key-0001",
                        "corr-same-key");
            });
            Future<ListingReportService.CreateResult> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.create(
                        request("OTHER_POLICY_CONCERN", false),
                        "report-same-key-0001",
                        "corr-same-key");
            });
            start.countDown();

            ListingReportService.CreateResult firstResult = first.get(20, TimeUnit.SECONDS);
            ListingReportService.CreateResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.response().reportId()).isEqualTo(secondResult.response().reportId());
            assertThat(count("listing_reports")).isEqualTo(1);
            assertThat(countWhere("outbox_events", "event_type = 'listing-report.received.v1'"))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void semanticDuplicateWindowExpiresAfterTwentyFourHours() {
        ListingReportService.CreateResult first = service.create(
                request("OTHER_POLICY_CONCERN", false),
                "report-expiry-key-0001",
                "corr-expiry");
        when(timeSource.now()).thenReturn(NOW.plus(25, ChronoUnit.HOURS));
        ListingReportService.CreateResult second = service.create(
                request("OTHER_POLICY_CONCERN", false),
                "report-expiry-key-0002",
                "corr-expiry");

        assertThat(first.response().reportId()).isNotEqualTo(second.response().reportId());
        assertThat(count("listing_reports")).isEqualTo(2);
        assertThat(countWhere("outbox_events", "event_type = 'listing-report.received.v1'"))
                .isEqualTo(2);
    }

    private ListingReportCreateRequest request(String reasonCode, boolean includeMedia) {
        ListingReportCreateRequest request = new ListingReportCreateRequest();
        request.setListingId(LISTING_ID);
        request.setReasonCode(reasonCode);
        request.setStatement("The public description appears inconsistent with the listing.");
        request.setListingMediaIds(includeMedia ? List.of(IMAGE_ID) : List.of());
        return request;
    }

    private String requestJson(String reasonCode, boolean includeMedia) {
        return """
                {
                  "listingId":"%s",
                  "reasonCode":"%s",
                  "statement":"The public description appears inconsistent with the listing.",
                  "listingMediaIds":%s
                }
                """.formatted(
                LISTING_ID,
                reasonCode,
                includeMedia ? "[\"" + IMAGE_ID + "\"]" : "[]");
    }

    private void seedPublicIndividualListing(
            String listingId,
            String ownerId,
            String imageId,
            String mediaId) {
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city,
                    public_region, status, moderation_status, publication_source,
                    published_at, version, created_at, updated_at
                ) values (?, 'INDIVIDUAL', ?, null, null, ?, 'Public bicycle',
                    'Public listing description', 'GOOD', null, 125.0000, 'USD',
                    true, null, 1, 'Irvine', 'CA', 'ACTIVE', 'APPROVED',
                    'ADMIN_REVIEW', ?, 7, ?, ?)
                """,
                listingId,
                ownerId,
                CATEGORY_ID,
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)),
                Timestamp.from(NOW.minus(2, ChronoUnit.DAYS)),
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)));
        jdbcTemplate.update("""
                insert into listing_media_objects (
                    id, listing_id, seller_type, individual_seller_user_id, business_id,
                    object_bucket, object_key, original_file_name, content_type, size_bytes,
                    checksum_sha256, upload_status, moderation_status, version, created_at, updated_at
                ) values (?, ?, 'INDIVIDUAL', ?, null, 'test-bucket', ?, 'bicycle.png',
                    'image/png', 8, ?, 'UPLOADED', 'APPROVED', 2, ?, ?)
                """,
                mediaId,
                listingId,
                ownerId,
                "reports/" + mediaId,
                "a".repeat(64),
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)),
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)));
        jdbcTemplate.update("""
                insert into listing_images (
                    id, listing_id, media_object_id, display_order, alt_text,
                    moderation_status, version, created_at, updated_at
                ) values (?, ?, ?, 0, 'Public bicycle', 'APPROVED', 1, ?, ?)
                """,
                imageId,
                listingId,
                mediaId,
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)),
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)));
    }

    private void replaceWithPublicBusinessListing() {
        deleteListingFixtures();
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city,
                    public_region, status, moderation_status, publication_source,
                    published_at, version, created_at, updated_at
                ) values (?, 'BUSINESS', null, ?, ?, ?, 'Public business bicycle',
                    'Public business listing description', 'GOOD', null, 125.0000, 'USD',
                    false, 'REP-01A-BIKE', 3, null, null, 'ACTIVE', 'NOT_SUBMITTED',
                    'BUSINESS_SELF_PUBLISHED', ?, 7, ?, ?)
                """,
                LISTING_ID,
                BUSINESS_ID,
                STORE_ID,
                CATEGORY_ID,
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)),
                Timestamp.from(NOW.minus(2, ChronoUnit.DAYS)),
                Timestamp.from(NOW.minus(1, ChronoUnit.DAYS)));
    }

    private void assertBusinessAuthFailureLeavesNoState(RuntimeException exception) {
        when(authServiceClient.checkBusinessListingPermission(anyString(), anyString()))
                .thenThrow(exception);
        assertThatThrownBy(() -> service.create(
                request("OTHER_POLICY_CONCERN", false),
                "report-auth-failure-key-0001",
                "corr-auth-failure"))
                .isSameAs(exception);
        assertNoReportPersistence();
    }

    private void assertCurrentUserFailureLeavesNoState(RuntimeException exception) {
        when(authServiceClient.requireCurrentUserForReport(anyString())).thenThrow(exception);
        assertThatThrownBy(() -> service.create(
                request("OTHER_POLICY_CONCERN", false),
                "report-actor-failure-key-0001",
                "corr-actor-failure"))
                .isSameAs(exception);
        assertNoReportPersistence();
    }

    private void assertNoReportPersistence() {
        assertThat(count("listing_report_idempotency")).isZero();
        assertThat(count("listing_report_duplicate_windows")).isZero();
        assertThat(count("listing_report_subject_snapshots")).isZero();
        assertThat(count("listing_reports")).isZero();
        assertThat(count("listing_report_history")).isZero();
        assertThat(count("listing_report_rate_limit_buckets")).isZero();
        assertThat(countWhere("moderation_cases", "case_type = 'LISTING_REPORT'")).isZero();
        assertThat(countWhere("outbox_events", "event_type = 'listing-report.received.v1'")).isZero();
    }

    private void insertListingReviewCase() {
        jdbcTemplate.update("""
                insert into moderation_cases (
                    id, case_type, subject_listing_id, subject_seller_type,
                    subject_individual_seller_user_id, subject_business_id,
                    submitted_by_user_id, status, priority, routing_queue,
                    assigned_admin_user_id, version, created_at, updated_at, resolved_at
                ) values ('01M00000000000000000000091', 'LISTING_REVIEW', ?, 'INDIVIDUAL',
                    ?, null, ?, 'OPEN', 'NORMAL', null, null, 0, ?, ?, null)
                """,
                LISTING_ID,
                OWNER_ID,
                OWNER_ID,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    private void clearReportData() {
        jdbcTemplate.update("delete from listing_report_idempotency");
        jdbcTemplate.update("delete from listing_report_duplicate_windows");
        jdbcTemplate.update("delete from listing_report_history");
        jdbcTemplate.update("delete from listing_report_evidence");
        jdbcTemplate.update("delete from listing_reports");
        jdbcTemplate.update("delete from listing_report_subject_media_snapshots");
        jdbcTemplate.update("delete from listing_report_subject_snapshots");
        jdbcTemplate.update("delete from moderation_cases where case_type = 'LISTING_REPORT'");
        jdbcTemplate.update("delete from listing_report_rate_limit_buckets");
        jdbcTemplate.update("delete from outbox_events where aggregate_type = 'LISTING_REPORT'");
    }

    private void deleteListingFixtures() {
        jdbcTemplate.update("delete from moderation_cases where subject_listing_id = ?", LISTING_ID);
        jdbcTemplate.update("delete from listing_images where listing_id = ?", LISTING_ID);
        jdbcTemplate.update("delete from listing_media_objects where listing_id = ?", LISTING_ID);
        jdbcTemplate.update("delete from listings where id = ?", LISTING_ID);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int countWhere(String table, String whereClause) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + whereClause,
                Integer.class);
    }

    private static final class TestRollbackException extends RuntimeException {
    }
}
