package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.search.operator.ListingSearchOperatorAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "listing.search.embedding.request-enabled=true",
        "listing.search.embedding.source-enabled=true",
        "listing.search.embedding.backfill.commands-enabled=true",
        "listing.search.embedding.backfill.status-enabled=true",
        "listing.search.embedding.backfill.page-size=1",
        "listing.search.embedding.backfill.max-listings=100",
        "listing.search.embedding.backfill.lease-duration=PT5S",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false",
        "listing.search.projection-sync.enabled=false",
        "listing.search.vector-sync.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ListingEmbeddingRequestBackfillIntegrationTests {
    private static final String WALNUT_ID = "01D00000000000000000000101";
    private static final String ADMIN_ID = "01A00000000000000000000941";
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired ListingEmbeddingRequestBackfillProcessor processor;
    @Autowired ListingEmbeddingRequestBackfillCandidateProcessor candidateProcessor;
    @Autowired ListingEmbeddingRequestBackfillRepository backfillRepository;
    @Autowired ListingSearchOperatorAuditRepository auditRepository;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from listing_search_vector_apply_work");
        jdbcTemplate.update("delete from listing_discovery_embedding_receipts");
        jdbcTemplate.update("delete from listing_search_operator_audit");
        jdbcTemplate.update("delete from listing_embedding_request_backfill_runs");
        jdbcTemplate.update("delete from outbox_events");
        jdbcTemplate.update("delete from listing_discovery_embedding_requests");
    }

    @Test
    void pagesToFiniteCompletionIncludesUnmodifiedVersionZeroSeedAndReplaysExactly()
            throws Exception {
        assertThat(version(WALNUT_ID)).isZero();

        ListingEmbeddingRequestBackfillRun first = complete(processor.start());

        assertThat(first.state()).isEqualTo("COMPLETED");
        assertThat(first.processedCount()).isPositive();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from listing_discovery_embedding_requests
                where listing_id = ? and listing_version = 0
                """, Long.class, WALNUT_ID)).isEqualTo(1);
        assertThat(version(WALNUT_ID)).isZero();

        String payload = jdbcTemplate.queryForObject("""
                select payload_json
                from outbox_events
                where aggregate_id = ?
                  and event_type = 'listing.discovery.embedding-requested'
                """, String.class, WALNUT_ID);
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.path("listingVersion").asLong()).isZero();
        assertThat(event.path("embeddingIdentity").path("model").asText())
                .isEqualTo("text-embedding-3-small");
        assertThat(payload).doesNotContain(
                "Walnut desktop radio",
                "individual_seller_user_id",
                "public_city",
                "email",
                "phone",
                "embeddingText",
                "vector");

        long requestCount = count("listing_discovery_embedding_requests");
        long outboxCount = count("outbox_events");
        ListingEmbeddingRequestBackfillRun replay = complete(processor.start());
        assertThat(replay.createdCount()).isZero();
        assertThat(replay.alreadyPresentCount()).isEqualTo(replay.processedCount());
        assertThat(count("listing_discovery_embedding_requests")).isEqualTo(requestCount);
        assertThat(count("outbox_events")).isEqualTo(outboxCount);
    }

    @Test
    void concurrentLiveAndBackfillCreationProduceOneRequestAndReferenceOnlyEvent()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Future<ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome>> futures;
        try {
            futures = List.of(
                    executor.submit(() -> {
                        start.await();
                        return candidateProcessor.process(WALNUT_ID);
                    }),
                    executor.submit(() -> {
                        start.await();
                        return candidateProcessor.process(WALNUT_ID);
                    }));
            start.countDown();
            assertThat(futures.get(0).get()).isIn(
                    ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.CREATED,
                    ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.ALREADY_PRESENT);
            assertThat(futures.get(1).get()).isIn(
                    ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.CREATED,
                    ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.ALREADY_PRESENT);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from listing_discovery_embedding_requests
                where listing_id = ? and listing_version = 0
                """, Long.class, WALNUT_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from outbox_events
                where aggregate_id = ?
                  and event_type = 'listing.discovery.embedding-requested'
                """, Long.class, WALNUT_ID)).isEqualTo(1);
    }

    @Test
    void expiredLeaseResumesAndEmbeddingAuditUsesProtectedReferenceOnlyColumns() {
        ListingEmbeddingRequestBackfillRun run = processor.start();
        if (!run.completed()) {
            jdbcTemplate.update("""
                    update listing_embedding_request_backfill_runs
                    set state = 'RUNNING',
                        lease_token = '01L00000000000000000000949',
                        lease_expires_at = ?,
                        updated_at = ?
                    where run_id = ?
                    """, java.sql.Timestamp.from(NOW.minusSeconds(10)),
                    java.sql.Timestamp.from(NOW.minusSeconds(10)), run.runId());
            assertThat(processor.resume(run.runId()).state())
                    .isIn("PENDING", "COMPLETED");
        }

        ListingEmbeddingRequestBackfillRun current = processor.status(run.runId());
        auditRepository.appendEmbeddingBackfill(
                "01T00000000000000000000949",
                ADMIN_ID,
                "EMBEDDING_RESUME",
                current.runId(),
                current.state(),
                current.state(),
                "REPLAYED",
                null,
                "corr-backfill",
                NOW);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from listing_search_operator_audit
                where embedding_backfill_run_id = ?
                  and action = 'EMBEDDING_RESUME'
                  and run_id is null
                """, Long.class, current.runId())).isEqualTo(1);
    }

    @Test
    void migrationEnforcesOneActiveRunAndValidStateCountContract() {
        backfillRepository.insert(
                "01R00000000000000000000951",
                WALNUT_ID,
                NOW);

        assertThatThrownBy(() -> backfillRepository.insert(
                "01R00000000000000000000952",
                WALNUT_ID,
                NOW))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                update listing_embedding_request_backfill_runs
                set processed_count = 2, created_count = 0,
                    already_present_count = 0, skipped_count = 0
                where run_id = '01R00000000000000000000951'
                """))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private ListingEmbeddingRequestBackfillRun complete(
            ListingEmbeddingRequestBackfillRun run) {
        ListingEmbeddingRequestBackfillRun current = run;
        int attempts = 0;
        while (!current.completed() && attempts++ < 100) {
            current = processor.resume(current.runId());
        }
        assertThat(attempts).isLessThan(100);
        return current;
    }

    private long version(String listingId) {
        return jdbcTemplate.queryForObject(
                "select version from listings where id = ?",
                Long.class,
                listingId);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    }
}
