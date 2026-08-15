package com.msb.ecom.product_service.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "listing.search.projection-sync.enabled=false",
        "listing.search.promotion.rebuild-enabled=false",
        "listing.search.promotion.promotion-enabled=false",
        "listing.search.vector-backfill.enabled=false",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false"
})
class ListingSearchPromotionFenceIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-23T13:00:00Z");

    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    static {
        mysql.start();
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    ListingSearchPromotionRepository promotionRepository;

    @Autowired
    ListingSearchProjectionWorkRepository workRepository;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from listing_search_rebuild_runs");
        jdbcTemplate.update("delete from listing_search_projection_work");
    }

    @Test
    void onlyOneActiveRunCanOwnDualWriteTarget() {
        transactionTemplate.executeWithoutResult(status -> promotionRepository.insertPreparingRun(
                "01R00000000000000000000701",
                "marketplace-listings-v2-g20260723130000001",
                "marketplace-listings-v1",
                "marketplace-listings-v1",
                0,
                NOW));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> promotionRepository.insertPreparingRun(
                        "01R00000000000000000000702",
                        "marketplace-listings-v2-g20260723130000002",
                        "marketplace-listings-v1",
                        "marketplace-listings-v1",
                        0,
                        NOW)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void exclusivePromotionFenceBlocksMutationIntentCommitUntilReleased() throws Exception {
        CountDownLatch exclusiveHeld = new CountDownLatch(1);
        CountDownLatch releaseExclusive = new CountDownLatch(1);
        CountDownLatch sharedAttempted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> promotion = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                promotionRepository.acquirePromotionFenceExclusive(10);
                exclusiveHeld.countDown();
                await(releaseExclusive);
            }));
            assertThat(exclusiveHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> mutation = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                sharedAttempted.countDown();
                promotionRepository.acquireMutationFenceShared(10);
                workRepository.insert(
                        "01W00000000000000000000701",
                        "01L00000000000000000000701",
                        1,
                        "UPSERT",
                        NOW);
            }));
            assertThat(sharedAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from listing_search_projection_work", Long.class)).isZero();

            releaseExclusive.countDown();
            promotion.get(5, TimeUnit.SECONDS);
            mutation.get(5, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_search_projection_work", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select work_sequence from listing_search_projection_work", Long.class))
                .isPositive();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test fence.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test fence.", exception);
        }
    }
}
