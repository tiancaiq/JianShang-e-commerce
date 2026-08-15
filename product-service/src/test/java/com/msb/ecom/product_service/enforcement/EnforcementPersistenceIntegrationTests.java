package com.msb.ecom.product_service.enforcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.security.AdminPermission;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.search.hybrid.ListingHybridSearchRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class EnforcementPersistenceIntegrationTests {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog").withUsername("catalog").withPassword("catalog");
    static EnforcementRepository repository;
    static JdbcTemplate jdbc;
    static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/catalog").target("202608120000").load().migrate();
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/catalog").load().migrate();
        dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new EnforcementRepository(jdbc, new ObjectMapper());
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        jdbc.update("""
                insert into categories (id, slug, name, status, display_order, created_at, updated_at)
                values (?, 'enforcement-test', 'Enforcement test', 'ACTIVE', 0, ?, ?)
                """, "01ARZ3NDEKTSV4RRFFQ69G5FBA", Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity, public_city,
                    public_region, status, moderation_status, version, created_at, updated_at
                ) values (?, 'INDIVIDUAL', ?, ?, 'Test', 'Test listing', 'GOOD', ?, 'USD', true,
                    1, 'City', 'Region', 'ACTIVE', 'APPROVED', 7, ?, ?)
                """, "01ARZ3NDEKTSV4RRFFQ69G5FBB", "01ARZ3NDEKTSV4RRFFQ69G5FBC",
                "01ARZ3NDEKTSV4RRFFQ69G5FBA", BigDecimal.TEN, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity, public_city,
                    public_region, status, moderation_status, version, created_at, updated_at
                ) values (?, 'INDIVIDUAL', ?, ?, 'Visibility test', 'Visibility test listing', 'GOOD', ?,
                    'USD', true, 1, 'City', 'Region', 'ACTIVE', 'APPROVED', 1, ?, ?)
                """, "01ARZ3NDEKTSV4RRFFQ69G5FBM", "01ARZ3NDEKTSV4RRFFQ69G5FBN",
                "01ARZ3NDEKTSV4RRFFQ69G5FBA", BigDecimal.ONE, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity, public_city,
                    public_region, status, moderation_status, version, created_at, updated_at
                ) values (?, 'INDIVIDUAL', ?, ?, 'Overlap test', 'Overlap test listing', 'GOOD', ?,
                    'USD', true, 1, 'City', 'Region', 'ACTIVE', 'APPROVED', 2, ?, ?)
                """, "01ARZ3NDEKTSV4RRFFQ69G5FBR", "01ARZ3NDEKTSV4RRFFQ69G5FBS",
                "01ARZ3NDEKTSV4RRFFQ69G5FBA", BigDecimal.ONE, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity, public_city,
                    public_region, status, moderation_status, version, created_at, updated_at
                ) values (?, 'INDIVIDUAL', ?, ?, 'Removed test', 'Removed test listing', 'GOOD', ?,
                    'USD', true, 1, 'City', 'Region', 'REMOVED_BY_ADMIN', 'REJECTED', 4, ?, ?)
                """, "01ARZ3NDEKTSV4RRFFQ69G5FBW", "01ARZ3NDEKTSV4RRFFQ69G5FBX",
                "01ARZ3NDEKTSV4RRFFQ69G5FBA", BigDecimal.ONE, Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void persistsListingActionEventFingerprintAndPreventsHardDeletion() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        String actionId = "01ARZ3NDEKTSV4RRFFQ69G5FBD";
        String key = "product-enforcement-1";
        assertThat(repository.lockListing("01ARZ3NDEKTSV4RRFFQ69G5FBB").orElseThrow().version()).isEqualTo(7);
        assertThat(repository.reserve("CREATE", key, "b".repeat(64), now)).isTrue();
        repository.insert(new EnforcementRepository.Action(actionId, "01ARZ3NDEKTSV4RRFFQ69G5FBB",
                ActionType.SUSPEND, Set.of(Scope.LISTING_PUBLIC_VISIBILITY), 0, now, null,
                "POLICY_ABUSE", "Human reason", null, 7, "01ARZ3NDEKTSV4RRFFQ69G5FBE",
                "Platform administrator", now, null, "correlation-2", "01ARZ3NDEKTSV4RRFFQ69G5FBF",
                key, "b".repeat(64), Map.of("policyReference", "POL-42")));
        repository.event(new EnforcementRepository.Event("01ARZ3NDEKTSV4RRFFQ69G5FBG", actionId,
                "CREATED", null, "ACTIVE", now, "01ARZ3NDEKTSV4RRFFQ69G5FBE", "Platform administrator",
                "POLICY_ABUSE", "Human reason", "correlation-2", "01ARZ3NDEKTSV4RRFFQ69G5FBH",
                Map.of("policyReference", "POL-42")));
        repository.complete("CREATE", key, actionId, now);
        assertThat(repository.action(actionId, false).orElseThrow().scopes())
                .containsExactly(Scope.LISTING_PUBLIC_VISIBILITY);
        assertThat(repository.timeline("01ARZ3NDEKTSV4RRFFQ69G5FBB"))
                .filteredOn(row -> row.actionId().equals(actionId)).hasSize(1);
        assertThat(repository.reserve("CREATE", key, "b".repeat(64), now)).isFalse();
        assertThatThrownBy(() -> jdbc.update("delete from enforcement_actions where id = ?", actionId))
                .isInstanceOf(Exception.class);
    }

    @Test
    void concurrentIdenticalServiceCommandsCreateOneActionAndReplayOneResult() throws Exception {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        AuthServiceClient auth = mock(AuthServiceClient.class);
        when(actors.currentActor()).thenReturn(new CurrentActor(
                "subject", "token", "admin@example.test", "Safety Admin", true));
        when(auth.requirePlatformAdmin("token")).thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                "01ARZ3NDEKTSV4RRFFQ69G5FBE", "PLATFORM_ADMIN", List.of("TRUST_AND_SAFETY_ADMIN"),
                List.of(AdminPermission.LISTING_SUSPEND.id(), AdminPermission.LISTING_REINSTATE.id()), "ACTIVE"));
        EnforcementService service = new EnforcementService(repository, actors, auth, new UlidGenerator());
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CreateCommand command = new CreateCommand(TargetType.LISTING, "01ARZ3NDEKTSV4RRFFQ69G5FBB",
                ActionType.SUSPEND, Set.of(Scope.LISTING_PURCHASABILITY), "POLICY_ABUSE", "Concurrent reason",
                null, Instant.parse("2026-08-12T00:00:00Z"), null, 7L,
                "concurrent-product-enforcement", Map.of(), false);
        Callable<EnforcementContracts.Result> task = () -> transactions.execute(status -> service.create(command));

        String actionId;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(task, task));
            String first = futures.get(0).get().enforcementActionId();
            String second = futures.get(1).get().enforcementActionId();
            assertThat(first).isEqualTo(second);
            actionId = first;
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from enforcement_actions where idempotency_key = ?", Integer.class,
                "concurrent-product-enforcement")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from enforcement_events e join enforcement_actions a
                  on a.id = e.enforcement_action_id where a.idempotency_key = ?
                """, Integer.class, "concurrent-product-enforcement")).isEqualTo(1);

        RevokeCommand revoke = new RevokeCommand(actionId, 0L, "RESTORED", "Concurrent restoration",
                "concurrent-product-revoke", Map.of(), false);
        Callable<EnforcementContracts.Result> revokeTask = () ->
                transactions.execute(status -> service.revoke(revoke));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.of(revokeTask, revokeTask));
            assertThat(futures.get(0).get().enforcementActionId())
                    .isEqualTo(futures.get(1).get().enforcementActionId()).isEqualTo(actionId);
            assertThat(futures.get(0).get().version()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from enforcement_events where enforcement_action_id = ? and event_type = 'REVOKED'",
                Integer.class, actionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select version from enforcement_actions where id = ?", Long.class, actionId)).isEqualTo(1L);
    }

    @Test
    void rollbackLeavesNoPartialIdempotencyActionScopeOrEvent() {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Instant now = Instant.parse("2026-08-12T00:00:00.123456Z");
        String key = "rollback-product-enforcement";
        String actionId = "01ARZ3NDEKTSV4RRFFQ69G5FBJ";

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            repository.reserve("CREATE", key, "d".repeat(64), now);
            repository.insert(new EnforcementRepository.Action(actionId, "01ARZ3NDEKTSV4RRFFQ69G5FBB",
                    ActionType.SUSPEND, Set.of(Scope.LISTING_PUBLIC_VISIBILITY), 0, now, null,
                    "POLICY_ABUSE", "Rollback reason", null, 7, "01ARZ3NDEKTSV4RRFFQ69G5FBE",
                    "Platform administrator", now, null, "rollback-correlation",
                    "01ARZ3NDEKTSV4RRFFQ69G5FBK", key, "d".repeat(64), Map.of()));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "select count(*) from enforcement_command_idempotency where idempotency_key = ?",
                Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from enforcement_actions where id = ?", Integer.class, actionId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from enforcement_action_scopes where enforcement_action_id = ?",
                Integer.class, actionId)).isZero();
    }

    @Test
    void visibilityRestrictionImmediatelyHidesAndRevocationRestoresPublicRead() {
        String listingId = "01ARZ3NDEKTSV4RRFFQ69G5FBM";
        String actionId = "01ARZ3NDEKTSV4RRFFQ69G5FBP";
        Instant now = Instant.now().minusSeconds(60);
        ListingDraftRepository listings = new ListingDraftRepository(jdbc);
        ListingHybridSearchRepository hybridSearch = new ListingHybridSearchRepository(jdbc);
        assertThat(listings.findPublicListingById(listingId)).isPresent();
        assertThat(listings.findPublicListings(100)).extracting("id").contains(listingId);
        assertThat(listings.findPublicListingsBySellerType("INDIVIDUAL", 100))
                .extracting("id").contains(listingId);
        assertThat(listings.findPublicListingsByIds(List.of(listingId))).extracting("id").contains(listingId);
        assertThat(listings.findAllPublicListingsForSearchIndex()).extracting("id").contains(listingId);
        assertThat(hybridSearch.findCurrentEligibleByIds(List.of(listingId)))
                .extracting("listingId").containsExactly(listingId);

        repository.insert(new EnforcementRepository.Action(actionId, listingId, ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), 0, now, null, "POLICY", "Visibility reason",
                null, 1, "01ARZ3NDEKTSV4RRFFQ69G5FBE", "Platform administrator", now, null,
                "visibility-correlation", "01ARZ3NDEKTSV4RRFFQ69G5FBQ", "visibility-key",
                "e".repeat(64), Map.of()));
        assertThat(listings.findPublicListingById(listingId)).isEmpty();
        assertThat(listings.findPublicListings(100)).extracting("id").doesNotContain(listingId);
        assertThat(listings.findPublicListingsBySellerType("INDIVIDUAL", 100))
                .extracting("id").doesNotContain(listingId);
        assertThat(listings.findPublicListingsByIds(List.of(listingId))).isEmpty();
        assertThat(listings.findAllPublicListingsForSearchIndex()).extracting("id").doesNotContain(listingId);
        assertThat(hybridSearch.findCurrentEligibleByIds(List.of(listingId))).isEmpty();

        jdbc.update("update enforcement_actions set revoked_at = ?, version = version + 1 where id = ?",
                Timestamp.from(Instant.now()), actionId);
        assertThat(listings.findPublicListingById(listingId)).isPresent();
        assertThat(listings.findPublicListings(100)).extracting("id").contains(listingId);
        assertThat(listings.findPublicListingsByIds(List.of(listingId))).extracting("id").contains(listingId);
        assertThat(hybridSearch.findCurrentEligibleByIds(List.of(listingId)))
                .extracting("listingId").containsExactly(listingId);
        assertThat(jdbc.queryForObject("select status from listings where id = ?", String.class, listingId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void expiredAndOverlappingVisibilityActionsRecalculateWithoutChangingListingState() {
        String listingId = "01ARZ3NDEKTSV4RRFFQ69G5FBR";
        Instant now = Instant.now();
        ListingDraftRepository listings = new ListingDraftRepository(jdbc);

        repository.insert(action("01ARZ3NDEKTSV4RRFFQ69G5FBT", listingId,
                now.minusSeconds(120), now.minusSeconds(60), "expired-visibility-key"));
        assertThat(listings.findPublicListingById(listingId)).isPresent();

        String first = "01ARZ3NDEKTSV4RRFFQ69G5FBZ";
        String second = "01ARZ3NDEKTSV4RRFFQ69G5FBV";
        repository.insert(action(first, listingId, now.minusSeconds(30), null, "overlap-visibility-one"));
        repository.insert(action(second, listingId, now.minusSeconds(20), null, "overlap-visibility-two"));
        assertThat(listings.findPublicListingById(listingId)).isEmpty();

        jdbc.update("update enforcement_actions set revoked_at = ?, version = version + 1 where id = ?",
                Timestamp.from(now), first);
        assertThat(listings.findPublicListingById(listingId)).isEmpty();

        jdbc.update("update enforcement_actions set revoked_at = ?, version = version + 1 where id = ?",
                Timestamp.from(now), second);
        assertThat(listings.findPublicListingById(listingId)).isPresent();
        assertThat(jdbc.queryForObject("select status from listings where id = ?", String.class, listingId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void revokingEnforcementNeverRestoresAdministrativelyRemovedListing() {
        String listingId = "01ARZ3NDEKTSV4RRFFQ69G5FBW";
        String actionId = "01ARZ3NDEKTSV4RRFFQ69G5FBY";
        Instant now = Instant.now();
        ListingDraftRepository listings = new ListingDraftRepository(jdbc);

        repository.insert(action(actionId, listingId, now.minusSeconds(30), null, "removed-visibility-key"));
        assertThat(listings.findPublicListingById(listingId)).isEmpty();
        jdbc.update("update enforcement_actions set revoked_at = ?, version = version + 1 where id = ?",
                Timestamp.from(now), actionId);

        assertThat(listings.findPublicListingById(listingId)).isEmpty();
        assertThat(jdbc.queryForObject("select status from listings where id = ?", String.class, listingId))
                .isEqualTo("REMOVED_BY_ADMIN");
    }

    private static EnforcementRepository.Action action(
            String actionId,
            String listingId,
            Instant effectiveAt,
            Instant expiresAt,
            String idempotencyKey) {
        return new EnforcementRepository.Action(actionId, listingId, ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), 0, effectiveAt, expiresAt,
                "POLICY", "Visibility reason", null, 2, "01ARZ3NDEKTSV4RRFFQ69G5FBE",
                "Platform administrator", effectiveAt, null, "visibility-correlation",
                actionId, idempotencyKey, "f".repeat(64), Map.of());
    }
}
