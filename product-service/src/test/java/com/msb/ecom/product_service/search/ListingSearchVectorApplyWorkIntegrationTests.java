package com.msb.ecom.product_service.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "listing.search.vector-sync.enabled=false",
        "listing.search.vector-sync.worker-enabled=false",
        "listing.search.embedding.result-enabled=false",
        "listing.search.projection-sync.enabled=false",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ListingSearchVectorApplyWorkIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-23T16:00:00Z");
    private static final String REQUEST_ID = "01R00000000000000000001001";
    private static final String EVENT_ID = "01E00000000000000000001001";
    private static final String LISTING_ID = "01L00000000000000000001001";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ListingSearchVectorApplyWorkRepository workRepository;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from listing_search_vector_apply_work");
        jdbcTemplate.update("delete from listing_discovery_embedding_receipts");
        jdbcTemplate.update("delete from listing_discovery_embedding_requests");
        insertReceipt();
    }

    @Test
    void migrationAndConcurrentRequestDedupProduceOneMetadataOnlyWorkRow()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of(
                    executor.submit(() -> insertAfter(start, "01W00000000000000000001001")),
                    executor.submit(() -> insertAfter(start, "01W00000000000000000001002")));
            start.countDown();
            assertThat(futures.stream().map(this::result).filter(Boolean::booleanValue))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_search_vector_apply_work",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'listing_search_vector_apply_work'
                """, String.class))
                .noneMatch(name -> name.matches(
                        "(?i).*(vector|embedding_text|prompt|body|provider_payload|seller|actor).*"));
    }

    @Test
    void expiredLeaseRetryAndRestartRecoveryRemainDeterministic() {
        assertThat(workRepository.insertIfAbsent(
                "01W00000000000000000001003",
                REQUEST_ID,
                LISTING_ID,
                7,
                NOW)).isTrue();

        assertThat(workRepository.claimBatch(
                "01C00000000000000000001001",
                NOW,
                NOW.plusSeconds(1),
                10)).hasSize(1);
        assertThat(workRepository.claimBatch(
                "01C00000000000000000001002",
                NOW.plusMillis(500),
                NOW.plusSeconds(2),
                10)).isEmpty();
        assertThat(workRepository.claimBatch(
                "01C00000000000000000001002",
                NOW.plusSeconds(2),
                NOW.plusSeconds(3),
                10)).hasSize(1);
        assertThat(workRepository.markRetry(
                "01W00000000000000000001003",
                "01C00000000000000000001002",
                NOW.plusSeconds(4),
                "TARGET_NOT_READY")).isTrue();
        assertThat(workRepository.claimBatch(
                "01C00000000000000000001003",
                NOW.plusSeconds(3),
                NOW.plusSeconds(4),
                10)).isEmpty();
        ListingSearchVectorApplyWork recovered = workRepository.claimBatch(
                "01C00000000000000000001003",
                NOW.plusSeconds(5),
                NOW.plusSeconds(6),
                10).get(0);
        assertThat(recovered.attemptCount()).isEqualTo(1);
        assertThat(workRepository.markApplied(
                recovered.workId(),
                "01C00000000000000000001003",
                NOW.plusSeconds(5))).isTrue();
        assertThat(workRepository.pendingCount()).isZero();
    }

    private boolean insertAfter(CountDownLatch start, String workId) throws Exception {
        start.await();
        return workRepository.insertIfAbsent(
                workId, REQUEST_ID, LISTING_ID, 7, NOW);
    }

    private boolean result(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void insertReceipt() {
        jdbcTemplate.update("""
                insert into listing_discovery_embedding_requests (
                    request_id, event_id, listing_id, listing_version,
                    document_schema_version, document_hash,
                    embedding_input_schema_version, embedding_input_hash,
                    normalizer_version, redactor_version, language,
                    embedding_provider, embedding_model, embedding_dimensions,
                    state, created_at
                ) values (?, ?, ?, 7,
                    'MARKETPLACE_LISTING_DISCOVERY_V2', ?,
                    'MARKETPLACE_LISTING_EMBEDDING_TEXT_V1', ?,
                    'NFKC_WHITESPACE_V1', 'PUBLIC_CONTACT_REDACTION_V1', 'und',
                    'openai', 'text-embedding-3-small', 1536,
                    'REQUESTED', ?)
                """,
                REQUEST_ID,
                EVENT_ID,
                LISTING_ID,
                "a".repeat(64),
                "b".repeat(64),
                Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into listing_discovery_embedding_receipts (
                    request_id, listing_id, listing_version,
                    document_schema_version, document_hash,
                    embedding_input_schema_version, embedding_input_hash,
                    normalizer_version, redactor_version, language,
                    embedding_provider, embedding_model, embedding_dimensions,
                    vector_hash, vector_bytes, accepted_at
                ) values (?, ?, 7,
                    'MARKETPLACE_LISTING_DISCOVERY_V2', ?,
                    'MARKETPLACE_LISTING_EMBEDDING_TEXT_V1', ?,
                    'NFKC_WHITESPACE_V1', 'PUBLIC_CONTACT_REDACTION_V1', 'und',
                    'openai', 'text-embedding-3-small', 1536,
                    ?, ?, ?)
                """,
                REQUEST_ID,
                LISTING_ID,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                new byte[6144],
                Timestamp.from(NOW));
    }
}
