package com.msb.ecom.product_service.search.hybrid;

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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "listing.search.hybrid.enabled=false",
        "listing.search.projection-sync.enabled=false",
        "listing.search.vector-sync.enabled=false",
        "listing.search.vector-sync.worker-enabled=false",
        "listing.search.embedding.request-enabled=false",
        "listing.search.embedding.source-enabled=false",
        "listing.search.embedding.result-enabled=false",
        "listing.knowledge.publication.backfill-enabled=false",
        "listing.knowledge.publication.publisher-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ListingHybridSearchRepositoryIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
    private static final String CATEGORY_ID = "01D00000000000000000000999";
    private static final String ELIGIBLE_ONE = "01D00000000000000000000901";
    private static final String ELIGIBLE_TWO = "01D00000000000000000000902";
    private static final String INELIGIBLE = "01D00000000000000000000903";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ListingHybridSearchRepository repository;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("delete from listings where id in (?, ?, ?)",
                ELIGIBLE_ONE, ELIGIBLE_TWO, INELIGIBLE);
        jdbcTemplate.update("delete from categories where id = ?", CATEGORY_ID);
        jdbcTemplate.update("""
                insert into categories (id, slug, name, status, display_order, created_at, updated_at)
                values (?, 'office', 'Office', 'ACTIVE', 0, ?, ?)
                """, CATEGORY_ID, Timestamp.from(NOW), Timestamp.from(NOW));
        insert(ELIGIBLE_ONE, "Current one", "ACTIVE", "APPROVED", 1, 8);
        insert(ELIGIBLE_TWO, "Current two", "ACTIVE", "APPROVED", 1, 9);
        insert(INELIGIBLE, "Removed", "REMOVED_BY_ADMIN", "APPROVED", 1, 10);
    }

    @Test
    void revalidatesCurrentEligibilityAndFactsWhilePreservingFusedOrder() {
        var results = repository.findCurrentEligibleByIds(
                List.of(ELIGIBLE_TWO, INELIGIBLE, ELIGIBLE_ONE));

        assertThat(results).extracting(ListingHybridSearchListing::listingId)
                .containsExactly(ELIGIBLE_TWO, ELIGIBLE_ONE);
        assertThat(results.getFirst().title()).isEqualTo("Current two");
        assertThat(results.getFirst().listingVersion()).isEqualTo(9);
        assertThat(results.getFirst().available()).isTrue();
        assertThat(results.get(1).priceAmount()).isEqualByComparingTo("55.00");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.publicCity()).isEqualTo("Irvine");
            assertThat(result.publicRegion()).isEqualTo("Orange County");
            assertThat(result.primaryImageUrl()).isNull();
        });
    }

    private void insert(
            String id,
            String title,
            String status,
            String moderation,
            int quantity,
            long version) {
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity,
                    public_city, public_region, status, moderation_status, publication_source,
                    published_at, version, created_at, updated_at
                )
                values (?, 'INDIVIDUAL', '01D00000000000000000000777', ?, ?, 'Safe public text',
                        'GOOD', 55.00, 'USD', false, ?, 'Irvine', 'Orange County', ?, ?,
                        'ADMIN_REVIEW', ?, ?, ?, ?)
                """,
                id,
                CATEGORY_ID,
                title,
                quantity,
                status,
                moderation,
                Timestamp.from(NOW),
                version,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }
}
