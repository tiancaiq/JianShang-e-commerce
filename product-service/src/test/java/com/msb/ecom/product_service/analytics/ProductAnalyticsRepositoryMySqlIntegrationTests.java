package com.msb.ecom.product_service.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.msb.ecom.product_service.analytics.ProductAnalyticsContracts.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ProductAnalyticsRepositoryMySqlIntegrationTests {
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-08T00:00:00Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant OLDEST_OPEN = Instant.parse("2026-07-30T00:00:00Z");

    private static final String CATEGORY_A = "01ANL3NDEKTSV4RRFFQ69G5FBA";
    private static final String CATEGORY_B = "01ANL3NDEKTSV4RRFFQ69G5FBB";
    private static final String LISTING_BEFORE = "01ANL3NDEKTSV4RRFFQ69G5FBC";
    private static final String LISTING_AT_FROM = "01ANL3NDEKTSV4RRFFQ69G5FBD";
    private static final String LISTING_INSIDE = "01ANL3NDEKTSV4RRFFQ69G5FBE";
    private static final String LISTING_AT_TO = "01ANL3NDEKTSV4RRFFQ69G5FBF";
    private static final String SELLER = "01ANL3NDEKTSV4RRFFQ69G5FBZ";
    private static final String ADMIN = "01ANL3NDEKTSV4RRFFQ69G5FBY";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog")
            .withUsername("catalog")
            .withPassword("catalog");

    private static JdbcTemplate jdbc;
    private static ProductAnalyticsService service;

    @BeforeAll
    static void migrateAndSeed() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/catalog")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        service = new ProductAnalyticsService(new ProductAnalyticsRepository(jdbc),
                Clock.fixed(GENERATED_AT, ZoneOffset.UTC));

        jdbc.update("delete from listing_engagement_stats where listing_id = ?",
                "01D00000000000000000000101");
        jdbc.update("delete from listings where id = ?", "01D00000000000000000000101");

        insertCategory(CATEGORY_A, "analytics-category-a", "Analytics Category A");
        insertCategory(CATEGORY_B, "analytics-category-b", "Analytics Category B");
        insertListing(LISTING_BEFORE, CATEGORY_A, "DRAFT", "NOT_SUBMITTED",
                Instant.parse("2026-07-31T23:59:59.999999Z"));
        insertListing(LISTING_AT_FROM, CATEGORY_A, "ACTIVE", "APPROVED", FROM);
        insertListing(LISTING_INSIDE, CATEGORY_B, "PENDING_REVIEW", "PENDING",
                Instant.parse("2026-08-02T12:00:00Z"));
        insertListing(LISTING_AT_TO, CATEGORY_A, "REMOVED_BY_ADMIN", "REJECTED", TO);

        insertCase("01ANL3NDEKTSV4RRFFQ69G5FBG", LISTING_BEFORE, "OPEN", null,
                OLDEST_OPEN, null);
        insertCase("01ANL3NDEKTSV4RRFFQ69G5FBH", LISTING_AT_FROM, "CLAIMED", ADMIN,
                Instant.parse("2026-08-01T01:00:00Z"), null);
        insertCase("01ANL3NDEKTSV4RRFFQ69G5FBJ", LISTING_INSIDE, "RESOLVED", ADMIN,
                Instant.parse("2026-08-02T00:00:00Z"), Instant.parse("2026-08-03T00:00:00Z"));
        insertCase("01ANL3NDEKTSV4RRFFQ69G5FBK", LISTING_AT_TO, "RESOLVED", ADMIN,
                Instant.parse("2026-08-07T00:00:00Z"), TO);

        insertDecision("01ANL3NDEKTSV4RRFFQ69G5FBL", LISTING_AT_FROM, "APPROVE", FROM);
        insertDecision("01ANL3NDEKTSV4RRFFQ69G5FBM", LISTING_INSIDE, "REJECT",
                Instant.parse("2026-08-04T00:00:00Z"));
        insertDecision("01ANL3NDEKTSV4RRFFQ69G5FBN", LISTING_INSIDE, "REQUEST_CHANGES", TO);
        insertDecision("01ANL3NDEKTSV4RRFFQ69G5FBP", LISTING_AT_TO, "ADMIN_REMOVE",
                Instant.parse("2026-08-05T00:00:00Z"));

        insertEnforcement("01ANL3NDEKTSV4RRFFQ69G5FBQ", LISTING_AT_FROM, "RESTRICT",
                FROM, null, FROM, null, "LISTING_PUBLIC_VISIBILITY");
        insertEnforcement("01ANL3NDEKTSV4RRFFQ69G5FBR", LISTING_AT_FROM, "SUSPEND",
                Instant.parse("2026-08-02T00:00:00Z"), null,
                Instant.parse("2026-08-02T00:00:00Z"), null, "LISTING_PURCHASABILITY");
        insertEnforcement("01ANL3NDEKTSV4RRFFQ69G5FBS", LISTING_INSIDE, "SUSPEND",
                Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-09T00:00:00Z"),
                Instant.parse("2026-08-03T00:00:00Z"), null, "LISTING_PUBLIC_VISIBILITY");
        insertEnforcement("01ANL3NDEKTSV4RRFFQ69G5FBT", LISTING_BEFORE, "RESTRICT",
                Instant.parse("2026-07-30T00:00:00Z"), null,
                Instant.parse("2026-07-30T00:00:00Z"), Instant.parse("2026-08-04T00:00:00Z"),
                "LISTING_PUBLIC_VISIBILITY");
        insertEnforcement("01ANL3NDEKTSV4RRFFQ69G5FBV", LISTING_AT_TO, "RESTRICT",
                TO, null, TO, null, "LISTING_PUBLIC_VISIBILITY");
    }

    @Test
    void computesDatabaseAggregatesWithUtcHalfOpenBoundariesAndDistinctCurrentState() {
        Summary result = service.summary(FROM, TO);

        assertThat(result.listings().totalListings()).isEqualTo(4);
        assertThat(result.listings().draftListings()).isEqualTo(1);
        assertThat(result.listings().activeListings()).isEqualTo(1);
        assertThat(result.listings().pendingReviewListings()).isEqualTo(1);
        assertThat(result.listings().removedByAdminListings()).isEqualTo(1);
        assertThat(result.listings().newListings()).isEqualTo(2);

        assertThat(result.moderation().openCount()).isEqualTo(2);
        assertThat(result.moderation().unassignedCount()).isEqualTo(1);
        assertThat(result.moderation().assignedCount()).isEqualTo(1);
        assertThat(result.moderation().oldestOpenAt()).isEqualTo(OLDEST_OPEN);
        assertThat(result.moderation().oldestOpenAgeSeconds())
                .isEqualTo(Duration.between(OLDEST_OPEN, GENERATED_AT).toSeconds());
        assertThat(result.moderation().resolvedInRange()).isEqualTo(1);
        assertThat(result.moderation().averageResolutionSeconds()).isEqualTo(86_400);
        assertThat(result.moderation().resolvedDecisionsInRange()).isEqualTo(2);
        assertThat(result.moderation().approvedInRange()).isEqualTo(1);
        assertThat(result.moderation().rejectedInRange()).isEqualTo(1);
        assertThat(result.moderation().changesRequestedInRange()).isZero();
        assertThat(result.moderation().rejectionRate()).isEqualByComparingTo("0.5000");

        assertThat(result.categories().topByCurrentListings()).first()
                .isEqualTo(new CategoryCount(CATEGORY_A, "Analytics Category A", 3));
        assertThat(result.categories().topByNewListings()).containsExactly(
                new CategoryCount(CATEGORY_A, "Analytics Category A", 1),
                new CategoryCount(CATEGORY_B, "Analytics Category B", 1));

        assertThat(result.listingEnforcement().targetType()).isEqualTo("LISTING");
        assertThat(result.listingEnforcement().createdInRange()).isEqualTo(3);
        assertThat(result.listingEnforcement().currentActive()).isEqualTo(3);
        assertThat(result.listingEnforcement().byActionType()).containsExactly(
                new EnforcementActionBreakdown("RESTRICT", 1, 2),
                new EnforcementActionBreakdown("SUSPEND", 2, 1));
        assertThat(result.listingEnforcement().byScope()).containsExactly(
                new EnforcementScopeBreakdown("LISTING_PUBLIC_VISIBILITY", 2, 2),
                new EnforcementScopeBreakdown("LISTING_PURCHASABILITY", 1, 1));

        ListingCreatedTrend trend = service.listingCreatedTrend(FROM, TO, Granularity.DAY);
        assertThat(trend.total()).isEqualTo(2);
        assertThat(trend.points()).extracting(TrendPoint::value)
                .containsExactly(1L, 1L, 0L, 0L, 0L, 0L, 0L);
    }

    @Test
    void forwardMigrationAddsOnlyTheFiveAnalyticsIndexes() {
        List<String> indexNames = jdbc.queryForList("""
                select distinct index_name
                from information_schema.statistics
                where table_schema = database()
                  and index_name like '%analytics%'
                order by index_name
                """, String.class);

        assertThat(indexNames).containsExactly(
                "idx_enforcement_actions_analytics_active",
                "idx_enforcement_actions_analytics_created",
                "idx_listing_moderation_decisions_analytics_created",
                "idx_listings_analytics_created",
                "idx_moderation_cases_analytics_resolved");
    }

    private static void insertCategory(String id, String slug, String name) {
        jdbc.update("""
                insert into categories (id, slug, name, status, display_order, created_at, updated_at)
                values (?, ?, ?, 'ACTIVE', 0, ?, ?)
                """, id, slug, name, Timestamp.from(FROM), Timestamp.from(FROM));
    }

    private static void insertListing(
            String id, String categoryId, String status, String moderationStatus, Instant createdAt) {
        jdbc.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity, public_city,
                    public_region, status, moderation_status, version, created_at, updated_at
                ) values (?, 'INDIVIDUAL', ?, ?, ?, 'Analytics test listing', 'GOOD', ?, 'USD',
                    true, 1, 'Irvine', 'California', ?, ?, 0, ?, ?)
                """, id, SELLER, categoryId, "Analytics " + id, BigDecimal.TEN,
                status, moderationStatus, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private static void insertCase(
            String id, String listingId, String status, String assignedAdmin,
            Instant createdAt, Instant resolvedAt) {
        jdbc.update("""
                insert into moderation_cases (
                    id, case_type, subject_listing_id, subject_seller_type,
                    subject_individual_seller_user_id, submitted_by_user_id, status, priority,
                    assigned_admin_user_id, created_at, updated_at, resolved_at
                ) values (?, 'LISTING_REVIEW', ?, 'INDIVIDUAL', ?, ?, ?, 'NORMAL', ?, ?, ?, ?)
                """, id, listingId, SELLER, SELLER, status, assignedAdmin,
                Timestamp.from(createdAt), Timestamp.from(resolvedAt == null ? createdAt : resolvedAt),
                resolvedAt == null ? null : Timestamp.from(resolvedAt));
    }

    private static void insertDecision(String id, String listingId, String decision, Instant createdAt) {
        jdbc.update("""
                insert into listing_moderation_decisions (
                    id, listing_id, decision, reason, reviewer_user_id, listing_version, created_at
                ) values (?, ?, ?, 'Analytics test decision', ?, 0, ?)
                """, id, listingId, decision, ADMIN, Timestamp.from(createdAt));
    }

    private static void insertEnforcement(
            String id, String listingId, String actionType, Instant effectiveAt, Instant expiresAt,
            Instant createdAt, Instant revokedAt, String scope) {
        jdbc.update("""
                insert into enforcement_actions (
                    id, target_type, target_id, action_type, effective_at, expires_at,
                    reason_code, reason, target_version_at_decision, source,
                    created_by_actor_id, created_by_actor_display_name, created_at, revoked_at,
                    correlation_id, request_id, idempotency_key, command_fingerprint, safe_metadata
                ) values (?, 'LISTING', ?, ?, ?, ?, 'ANALYTICS_TEST', 'Analytics test action',
                    0, 'SYSTEM', ?, 'Analytics test', ?, ?, ?, ?, ?, ?, json_object())
                """, id, listingId, actionType, Timestamp.from(effectiveAt),
                expiresAt == null ? null : Timestamp.from(expiresAt), ADMIN, Timestamp.from(createdAt),
                revokedAt == null ? null : Timestamp.from(revokedAt), "analytics-" + id,
                id, "analytics-" + id, "a".repeat(64));
        jdbc.update("""
                insert into enforcement_action_scopes (enforcement_action_id, scope)
                values (?, ?)
                """, id, scope);
    }
}
