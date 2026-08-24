package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.operations.ProductOperationsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "listing.search.projection-sync.enabled=false",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false"
})
class ListingSearchProjectionWorkIntegrationTests {

    private static final String CATEGORY_ID = "01K00000000000000000000251";
    private static final String LISTING_ID = "01L00000000000000000000251";
    private static final Instant NOW = Instant.parse("2026-07-23T09:00:00Z");

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
    ListingSearchProjectionWorkRepository repository;

    @Autowired
    ProductOperationsRepository operationsRepository;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from listing_search_projection_work");
        jdbcTemplate.update("delete from listings where id = ?", LISTING_ID);
        jdbcTemplate.update("delete from categories where id = ?", CATEGORY_ID);
        jdbcTemplate.update("""
                insert into categories (id, slug, name, status, display_order, created_at, updated_at)
                values (?, 'projection-test', 'Projection test', 'ACTIVE', 0, ?, ?)
                """, CATEGORY_ID, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity,
                    public_city, public_region, status, moderation_status, version, created_at, updated_at
                )
                values (?, 'INDIVIDUAL', '01U00000000000000000000251', ?, 'Desk', 'Desk',
                        'GOOD', 42.00, 'USD', false, 1, 'Irvine', 'Orange County',
                        'DRAFT', 'NOT_SUBMITTED', 0, ?, ?)
                """, LISTING_ID, CATEGORY_ID, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void listingMutationAndIntentRollbackTogether() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("update listings set version = 1 where id = ?", LISTING_ID);
            repository.insert(
                    "01W00000000000000000000251", LISTING_ID, 1, "DELETE", NOW);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select version from listings where id = ?", Long.class, LISTING_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from listing_search_projection_work", Long.class)).isZero();
    }

    @Test
    void duplicateListingVersionIntentIsDurablyRejected() {
        repository.insert("01W00000000000000000000252", LISTING_ID, 1, "DELETE", NOW);

        assertThatThrownBy(() -> repository.insert(
                "01W00000000000000000000253", LISTING_ID, 1, "UPSERT", NOW))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void boundedAdminReindexKeepsNormalIntentFenceAndPreservesReplayHistory() {
        transactionTemplate.executeWithoutResult(status -> {
            ProductOperationsRepository.ListingState listing = operationsRepository
                    .listingForUpdate(LISTING_ID).orElseThrow();
            operationsRepository.reindex(
                    listing, "01W00000000000000000000255", "correlation-one", NOW);
        });
        transactionTemplate.executeWithoutResult(status -> {
            ProductOperationsRepository.ListingState listing = operationsRepository
                    .listingForUpdate(LISTING_ID).orElseThrow();
            operationsRepository.reindex(
                    listing, "01W00000000000000000000256", "correlation-two", NOW.plusSeconds(1));
        });

        assertThat(jdbcTemplate.queryForList("""
                select replay_sequence
                from listing_search_projection_work
                where listing_id = ? and listing_version = 0
                order by replay_sequence
                """, Integer.class, LISTING_ID)).containsExactly(1, 2);

        repository.insert("01W00000000000000000000257", LISTING_ID, 0, "DELETE", NOW.plusSeconds(2));
        assertThatThrownBy(() -> repository.insert(
                "01W00000000000000000000258", LISTING_ID, 0, "UPSERT", NOW.plusSeconds(3)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void expiredClaimIsRecoveredAfterRestartBoundary() {
        repository.insert("01W00000000000000000000254", LISTING_ID, 1, "DELETE", NOW);

        List<ListingSearchProjectionWork> first =
                repository.claimBatch("01C00000000000000000000251", NOW, NOW.plusSeconds(60), 10);
        List<ListingSearchProjectionWork> blocked =
                repository.claimBatch("01C00000000000000000000252", NOW.plusSeconds(30), NOW.plusSeconds(90), 10);
        List<ListingSearchProjectionWork> recovered =
                repository.claimBatch("01C00000000000000000000253", NOW.plusSeconds(61), NOW.plusSeconds(121), 10);

        assertThat(first).extracting(ListingSearchProjectionWork::workId)
                .containsExactly("01W00000000000000000000254");
        assertThat(blocked).isEmpty();
        assertThat(recovered).extracting(ListingSearchProjectionWork::workId)
                .containsExactly("01W00000000000000000000254");
    }
}
