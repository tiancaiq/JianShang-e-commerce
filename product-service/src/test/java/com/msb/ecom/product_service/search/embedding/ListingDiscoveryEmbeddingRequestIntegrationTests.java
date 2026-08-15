package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.product_service.knowledge.ListingKnowledgeOutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "listing.search.embedding.request-enabled=true",
        "listing.search.embedding.source-enabled=true",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false",
        "listing.search.projection-sync.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ListingDiscoveryEmbeddingRequestIntegrationTests {

    private static final String CATEGORY_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ListingDiscoveryEmbeddingRequestRepository repository;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from outbox_events");
        jdbcTemplate.update("delete from listing_discovery_embedding_requests");
        jdbcTemplate.update("delete from listings where id = ?", LISTING_ID);
        jdbcTemplate.update("delete from categories where id = ?", CATEGORY_ID);
        jdbcTemplate.update("""
                insert into categories (id, slug, name, status, display_order, created_at, updated_at)
                values (?, 'furniture', 'Furniture', 'ACTIVE', 0, ?, ?)
                """, CATEGORY_ID, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity,
                    public_city, public_region, status, moderation_status, publication_source,
                    published_at, version, created_at, updated_at
                )
                values (?, 'INDIVIDUAL', '01ARZ3NDEKTSV4RRFFQ69G5FAC', ?, 'Desk',
                        'Public description', 'GOOD', 25.00, 'USD', false, 1,
                        'Irvine', 'Orange County', 'ACTIVE', 'APPROVED', 'ADMIN_REVIEW',
                        ?, 7, ?, ?)
                """, LISTING_ID, CATEGORY_ID, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void listingMutationRequestAndOutboxRollbackAtomically() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("update listings set version = 8 where id = ?", LISTING_ID);
            ListingDiscoveryEmbeddingRequest request = request(1, 8);
            assertThat(repository.insertIfAbsent(request)).isTrue();
            repository.insertOutbox(event(request), "LISTING_DISCOVERY_EMBEDDING:" + request.requestId());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select version from listings where id = ?", Long.class, LISTING_ID)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_discovery_embedding_requests", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox_events", Long.class)).isZero();
    }

    @Test
    void concurrentIdenticalIdentityCreatesOneRequestAndOneOutboxEvent() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            int suffix = index;
            tasks.add(() -> {
                start.await();
                return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    ListingDiscoveryEmbeddingRequest request = request(suffix, 7);
                    boolean inserted = repository.insertIfAbsent(request);
                    if (inserted) {
                        repository.insertOutbox(
                                event(request),
                                "LISTING_DISCOVERY_EMBEDDING:" + request.requestId());
                    }
                    return inserted;
                }));
            });
        }
        List<Future<Boolean>> futures;
        try {
            futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            assertThat(futures.stream().filter(this::result).count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_discovery_embedding_requests", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where event_type = ?",
                Long.class,
                "listing.discovery.embedding-requested")).isEqualTo(1);
    }

    private boolean result(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ListingDiscoveryEmbeddingRequest request(int suffix, long version) {
        String requestId = "01ARZ3NDEKTSV4RRFFQ69G5F" + character(suffix);
        String eventId = "01ARZ3NDEKTSV4RRFFQ69G5" + twoCharacters(suffix);
        return new ListingDiscoveryEmbeddingRequest(
                requestId,
                eventId,
                LISTING_ID,
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
                "REQUESTED",
                NOW.plusSeconds(suffix));
    }

    private ListingKnowledgeOutboxEvent event(ListingDiscoveryEmbeddingRequest request) {
        return new ListingKnowledgeOutboxEvent(
                request.eventId(),
                "listing-discovery-embedding-request-v1",
                LISTING_ID,
                "listing",
                LISTING_ID,
                "listing.discovery.embedding-requested",
                1,
                "product-service",
                request.createdAt(),
                request.eventId(),
                "{\"requestId\":\"" + request.requestId() + "\"}",
                0,
                request.createdAt());
    }

    private char character(int value) {
        return "ACDE".charAt(value - 1);
    }

    private String twoCharacters(int value) {
        return "A" + character(value);
    }
}
