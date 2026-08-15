package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.search.ListingSearchVectorCatchUpService;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "agent.internal-service-token=test-agent-token",
        "listing.search.embedding.result-enabled=true",
        "listing.search.embedding.request-enabled=false",
        "listing.search.embedding.source-enabled=false",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false",
        "listing.search.projection-sync.enabled=false",
        "listing.search.vector-sync.enabled=true",
        "listing.search.vector-sync.worker-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ListingDiscoveryEmbeddingReceiptIntegrationTests {

    private static final String TOKEN = "test-agent-token";
    private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String EVENT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAD";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE";
    private static final String CATEGORY_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAF";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ListingDraftRepository listingRepository;
    @Autowired ListingMediaRepository mediaRepository;
    @Autowired ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;
    @Autowired ListingDiscoveryEmbeddingRequestRepository requestRepository;
    @Autowired ListingDiscoveryEmbeddingReceiptRepository receiptRepository;
    @Autowired ListingDiscoveryEmbeddingResultService resultService;
    @Autowired ListingSearchVectorCatchUpService catchUpService;
    @Autowired ObjectMapper objectMapper;

    ListingDiscoveryEmbeddingSource source;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from listing_search_vector_apply_work");
        jdbcTemplate.update("delete from listing_discovery_embedding_receipts");
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
                values (?, 'INDIVIDUAL', '01ARZ3NDEKTSV4RRFFQ69G5FAG', ?, 'Desk',
                        'Public description', 'GOOD', 25.00, 'USD', false, 1,
                        'Irvine', 'Orange County', 'ACTIVE', 'APPROVED', 'ADMIN_REVIEW',
                        ?, 7, ?, ?)
                """, LISTING_ID, CATEGORY_ID, Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));
        PublicListingResponse listing = listingRepository.findPublicListingById(LISTING_ID)
                .orElseThrow();
        source = sourceBuilder.build(
                listing,
                7,
                mediaRepository.findPublicImagesByListingId(LISTING_ID));
        assertThat(requestRepository.insertIfAbsent(request())).isTrue();
    }

    @Test
    void migrationStoresExactlyOneCanonical6144ByteReceiptAndRebuildExcludesStale() {
        var acknowledgement = resultService.accept(TOKEN, REQUEST_ID, body(0.25d));

        assertThat(acknowledgement.outcome()).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("""
                select octet_length(vector_bytes)
                from listing_discovery_embedding_receipts
                where request_id = ?
                """, Integer.class, REQUEST_ID)).isEqualTo(6144);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from listing_search_vector_apply_work
                where request_id = ?
                """, Integer.class, REQUEST_ID)).isEqualTo(1);
        assertThat(receiptRepository.findExactCurrentForRebuild(
                LISTING_ID,
                7,
                source.documentHash(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536)).isPresent();

        jdbcTemplate.update("update listings set version = 8 where id = ?", LISTING_ID);

        assertThat(receiptRepository.findExactCurrentForRebuild(
                LISTING_ID,
                7,
                source.documentHash(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536)).isEmpty();
    }

    @Test
    void versionZeroCallbackStoresReceiptVectorWorkAndExactReplay() {
        jdbcTemplate.update("delete from listing_search_vector_apply_work");
        jdbcTemplate.update("delete from listing_discovery_embedding_receipts");
        jdbcTemplate.update("delete from listing_discovery_embedding_requests");
        jdbcTemplate.update("update listings set version = 0 where id = ?", LISTING_ID);
        PublicListingResponse listing = listingRepository.findPublicListingById(LISTING_ID)
                .orElseThrow();
        source = sourceBuilder.build(
                listing,
                0,
                mediaRepository.findPublicImagesByListingId(LISTING_ID));
        assertThat(requestRepository.insertIfAbsent(request(0))).isTrue();

        var acknowledgement = resultService.accept(TOKEN, REQUEST_ID, body(0, 0.25d));
        var replay = resultService.accept(TOKEN, REQUEST_ID, body(0, 0.25d));

        assertThat(acknowledgement.outcome()).isEqualTo("ACCEPTED");
        assertThat(replay.outcome()).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("""
                select listing_version
                from listing_discovery_embedding_receipts
                where request_id = ?
                """, Long.class, REQUEST_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select listing_version
                from listing_search_vector_apply_work
                where request_id = ?
                """, Long.class, REQUEST_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from listing_discovery_embedding_receipts
                where request_id = ?
                """, Integer.class, REQUEST_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from listing_search_vector_apply_work
                where request_id = ?
                """, Integer.class, REQUEST_ID)).isEqualTo(1);
        assertThat(receiptRepository.findExactCurrentForRebuild(
                LISTING_ID,
                0,
                source.documentHash(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536)).isPresent();
        assertThatThrownBy(() -> resultService.accept(TOKEN, REQUEST_ID, body(0, 0.5d)))
                .isInstanceOf(ListingDiscoveryEmbeddingIdempotencyConflictException.class);
    }

    @Test
    void vectorBackfillCursorReadsTheAuthoritativeVersionInStableIdOrder() {
        var page = listingRepository.findPublicListingsForVectorBackfillAfter(null, 501);

        assertThat(page).anySatisfy(row -> {
            assertThat(row.listing().id()).isEqualTo(LISTING_ID);
            assertThat(row.listingVersion()).isEqualTo(7);
        });
        assertThat(page).isSortedAccordingTo(
                java.util.Comparator.comparing(row -> row.listing().id()));
        assertThat(listingRepository.findPublicListingsForVectorBackfillAfter(LISTING_ID, 501))
                .allSatisfy(row -> assertThat(row.listing().id()).isGreaterThan(LISTING_ID));
    }

    @Test
    void concurrentIdenticalCallbacksReplayAndDifferentCallbacksConflict() throws Exception {
        List<Object> identical = runConcurrently(
                () -> resultService.accept(TOKEN, REQUEST_ID, body(0.25d)),
                () -> resultService.accept(TOKEN, REQUEST_ID, body(0.25d)));
        assertThat(identical)
                .allMatch(ListingDiscoveryEmbeddingResultAcknowledgement.class::isInstance);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_discovery_embedding_receipts",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_search_vector_apply_work",
                Integer.class)).isEqualTo(1);

        List<Object> conflicting = runConcurrently(
                () -> resultService.accept(TOKEN, REQUEST_ID, body(0.25d)),
                () -> resultService.accept(TOKEN, REQUEST_ID, body(0.5d)));
        assertThat(conflicting.stream()
                .filter(ListingDiscoveryEmbeddingResultAcknowledgement.class::isInstance)
                .count()).isEqualTo(1);
        assertThat(conflicting.stream()
                .filter(ListingDiscoveryEmbeddingIdempotencyConflictException.class::isInstance)
                .count()).isEqualTo(1);
    }

    @Test
    void receiptInsertRollsBackAtomically() {
        ListingDiscoveryEmbeddingResultRequest parsed =
                new ListingDiscoveryEmbeddingResultRequestParser(objectMapper)
                        .parse(body(0.25d));
        ListingDiscoveryEmbeddingReceipt receipt = new ListingDiscoveryEmbeddingReceipt(
                REQUEST_ID,
                LISTING_ID,
                7,
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536,
                parsed.vector().hash(),
                parsed.vector().bytes(),
                NOW);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(receiptRepository.insert(receipt)).isTrue();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(receiptRepository.findByRequestId(REQUEST_ID)).isEmpty();
    }

    @Test
    void receiptAndVectorIntentRollBackTogether() {
        ListingDiscoveryEmbeddingResultRequest parsed =
                new ListingDiscoveryEmbeddingResultRequestParser(objectMapper)
                        .parse(body(0.25d));
        ListingDiscoveryEmbeddingReceipt receipt = new ListingDiscoveryEmbeddingReceipt(
                REQUEST_ID,
                LISTING_ID,
                7,
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536,
                parsed.vector().hash(),
                parsed.vector().bytes(),
                NOW);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(receiptRepository.insert(receipt)).isTrue();
            jdbcTemplate.update("""
                    insert into listing_search_vector_apply_work (
                        work_id, request_id, listing_id, listing_version, state,
                        attempt_count, next_attempt_at, created_at, updated_at
                    ) values (?, ?, ?, 7, 'PENDING', 0, ?, ?, ?)
                    """,
                    "01W00000000000000000000601",
                    REQUEST_ID,
                    LISTING_ID,
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(receiptRepository.findByRequestId(REQUEST_ID)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_search_vector_apply_work",
                Integer.class)).isZero();
    }

    @Test
    void acceptedReceiptFromBeforeEnablementIsCaughtUpExactlyOnce() {
        ListingDiscoveryEmbeddingResultRequest parsed =
                new ListingDiscoveryEmbeddingResultRequestParser(objectMapper)
                        .parse(body(0.25d));
        assertThat(receiptRepository.insert(new ListingDiscoveryEmbeddingReceipt(
                REQUEST_ID,
                LISTING_ID,
                7,
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536,
                parsed.vector().hash(),
                parsed.vector().bytes(),
                NOW))).isTrue();

        var first = catchUpService.enqueueAfter(0);
        var replay = catchUpService.enqueueAfter(0);

        assertThat(first.enqueuedCount()).isEqualTo(1);
        assertThat(first.staleCount()).isZero();
        assertThat(replay.enqueuedCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_search_vector_apply_work",
                Integer.class)).isEqualTo(1);
    }

    private List<Object> runConcurrently(Callable<Object> first, Callable<Object> second)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = List.of(
                    executor.submit(() -> outcome(start, first)),
                    executor.submit(() -> outcome(start, second)));
            start.countDown();
            return futures.stream().map(this::future).toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private Object outcome(CountDownLatch start, Callable<Object> action) {
        try {
            start.await();
            return action.call();
        } catch (RuntimeException exception) {
            return exception;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private Object future(Future<Object> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private ListingDiscoveryEmbeddingRequest request() {
        return new ListingDiscoveryEmbeddingRequest(
                REQUEST_ID,
                EVENT_ID,
                LISTING_ID,
                7,
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536,
                "REQUESTED",
                NOW);
    }

    private ListingDiscoveryEmbeddingRequest request(long listingVersion) {
        return new ListingDiscoveryEmbeddingRequest(
                REQUEST_ID,
                EVENT_ID,
                LISTING_ID,
                listingVersion,
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536,
                "REQUESTED",
                NOW);
    }

    private byte[] body(double value) {
        return body(7, value);
    }

    private byte[] body(long listingVersion, double value) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("schemaVersion", "MARKETPLACE_LISTING_EMBEDDING_RESULT_V1");
            body.put("listingVersion", listingVersion);
            body.put("documentSchemaVersion", source.documentSchemaVersion());
            body.put("documentHash", source.documentHash());
            body.put("embeddingInputSchemaVersion", source.embeddingInputSchemaVersion());
            body.put("embeddingInputHash", source.embeddingInputHash());
            ObjectNode identity = body.putObject("embeddingIdentity");
            identity.put("provider", "openai");
            identity.put("model", "text-embedding-3-small");
            identity.put("dimensions", 1536);
            ArrayNode vector = body.putArray("vector");
            for (int index = 0; index < 1536; index++) {
                vector.add(value);
            }
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
