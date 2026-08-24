package com.msb.ecom.product_service.enforcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.EffectiveState;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Executor;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Outcome;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Replacement;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Request;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Response;
import com.msb.ecom.product_service.enforcement.EnforcementService.TrustedAdminActor;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.security.AdminPermission;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class ListingAppealResolutionPersistenceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    private static final String CATEGORY = "01ARZ3NDEKTSV4RRFFQ69G8AAB";
    private static final String EXECUTOR = "01ARZ3NDEKTSV4RRFFQ69G8AAC";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("catalog").withUsername("catalog").withPassword("catalog");

    static JdbcTemplate jdbc;
    static EnforcementRepository repository;
    static ListingAppealResolutionService service;
    static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/catalog").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new EnforcementRepository(jdbc, new ObjectMapper());
        EnforcementService enforcement = new EnforcementService(repository, mock(CurrentActorProvider.class),
                mock(AuthServiceClient.class), new UlidGenerator());
        service = new ListingAppealResolutionService(repository, enforcement,
                Clock.fixed(NOW, ZoneOffset.UTC));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("""
                insert into categories (id, slug, name, status, display_order, created_at, updated_at)
                values (?, 'appeal-resolution-test', 'Appeal resolution test', 'ACTIVE', 0, ?, ?)
                """, CATEGORY, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void modifiedResolutionAtomicallyRevokesOriginalCreatesLinkedReplacementAndReplays() {
        Fixture fixture = fixture("MOD", 3, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY));
        Request previewRequest = request(Outcome.MODIFIED, fixture, "modify-command",
                new Replacement(ActionType.RESTRICT, Set.of(Scope.LISTING_PUBLIC_VISIBILITY),
                        Instant.EPOCH, null, "APPEAL_ADJUSTED", "Adjusted listing restriction"), false, null);

        var preview = tx(() -> service.preview(fixture.appealId(), previewRequest));
        assertThat(preview.dryRun()).isTrue();
        assertThat(preview.currentEffectiveEnforcementState()).isEqualTo(EffectiveState.RESTRICTED);
        assertThat(preview.replacementEnforcementActionId()).isNull();
        assertThat(preview.warnings()).isEmpty();

        Request execute = request(Outcome.MODIFIED, fixture, "modify-command", previewRequest.replacement(),
                true, preview.confirmationToken());
        var result = tx(() -> service.execute(fixture.appealId(), execute));

        assertThat(result.dryRun()).isFalse();
        assertThat(result.replayed()).isFalse();
        assertThat(result.originalEnforcementVersion()).isEqualTo(1);
        assertThat(result.replacementEnforcementActionId()).isNotBlank();
        assertThat(result.replacementEnforcementVersion()).isZero();
        assertThat(result.currentEffectiveEnforcementState()).isEqualTo(EffectiveState.RESTRICTED);
        assertThat(jdbc.queryForObject("select revoked_by_actor_id from enforcement_actions where id = ?",
                String.class, fixture.actionId())).isEqualTo(EXECUTOR);
        assertThat(jdbc.queryForObject("select parent_enforcement_action_id from enforcement_actions where id = ?",
                String.class, result.replacementEnforcementActionId())).isEqualTo(fixture.actionId());
        assertThat(jdbc.queryForObject("select created_by_actor_id from enforcement_actions where id = ?",
                String.class, result.replacementEnforcementActionId())).isEqualTo(EXECUTOR);
        assertThat(jdbc.queryForMap("select reason_code, reason from enforcement_actions where id = ?",
                result.replacementEnforcementActionId()))
                .containsEntry("reason_code", "APPEAL_ADJUSTED")
                .containsEntry("reason", "Adjusted listing restriction");
        assertThat(jdbc.queryForObject("select count(*) from enforcement_events where enforcement_action_id = ?",
                Integer.class, fixture.actionId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from enforcement_events where enforcement_action_id = ?",
                Integer.class, result.replacementEnforcementActionId())).isEqualTo(1);

        Request recovery = new Request(execute.outcome(), execute.enforcementActionId(),
                execute.expectedEnforcementVersion(), execute.expectedTargetVersion(), execute.reasonCode(),
                execute.reason(), execute.replacement(), execute.idempotencyKey(), execute.executor(),
                execute.safeMetadata(), execute.confirmed(), execute.confirmationToken(), true);
        var replay = tx(() -> service.execute(fixture.appealId(), recovery));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replacementEnforcementActionId()).isEqualTo(result.replacementEnforcementActionId());
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isEqualTo(1);
        assertThatThrownBy(() -> tx(() -> service.execute(fixture.appealId(),
                request(Outcome.MODIFIED, fixture, "modify-command", new Replacement(ActionType.RESTRICT,
                        Set.of(Scope.LISTING_PURCHASABILITY), Instant.EPOCH, null,
                        "APPEAL_ADJUSTED", "Adjusted listing restriction"), true,
                        preview.confirmationToken())))).isInstanceOf(EnforcementExceptions.Conflict.class)
                .hasMessageContaining("different appeal resolution");
    }

    @Test
    void revokePreservesWeakerOverlapAndRejectsPreviewAfterOverlapChanges() {
        Fixture fixture = fixture("REV", 4, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY));
        insertAction(fixture.listingId(), fixture.weakerActionId(), ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), "weaker-" + fixture.suffix());
        Request request = request(Outcome.REVOKED, fixture, "revoke-command", null, false, null);
        var preview = tx(() -> service.preview(fixture.appealId(), request));
        assertThat(preview.currentEffectiveEnforcementState()).isEqualTo(EffectiveState.RESTRICTED);
        assertThat(preview.warnings()).isNotEmpty();

        jdbc.update("update enforcement_actions set version = version + 1, revoked_at = ? where id = ?",
                Timestamp.from(NOW.minusSeconds(1)), fixture.weakerActionId());
        Request execute = request(Outcome.REVOKED, fixture, "revoke-command", null,
                true, preview.confirmationToken());
        assertThatThrownBy(() -> tx(() -> service.execute(fixture.appealId(), execute)))
                .isInstanceOf(EnforcementExceptions.Conflict.class)
                .hasMessageContaining("changed after the resolution preview");
        assertThat(jdbc.queryForObject("select revoked_at is null from enforcement_actions where id = ?",
                Boolean.class, fixture.actionId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isZero();
    }

    @Test
    void trustedBoundaryRequiresAppealAndTargetSpecificPermissions() {
        Fixture fixture = fixture("PER", 5, ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY));
        Executor missingAppeal = new Executor(EXECUTOR, "Actual executor",
                Set.of(AdminPermission.LISTING_REINSTATE.id()));
        Request request = new Request(Outcome.REVOKED, fixture.actionId(), 0L, fixture.listingVersion(),
                "APPEAL_REVOKED", "Resolution reason", null, null, missingAppeal,
                Map.of("origin", "APPEAL_RESOLUTION"), false, null, false);
        assertThatThrownBy(() -> service.preview(fixture.appealId(), request))
                .isInstanceOf(ListingAuthorizationException.class)
                .hasMessageContaining("admin.appeal.resolve");
    }

    @Test
    void replacementFailureRollsBackRevocationEventAndCompositeReservation() {
        Fixture fixture = fixture("ROL", 6, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY));
        Replacement replacement = new Replacement(ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), Instant.EPOCH, null,
                "APPEAL_ADJUSTED", "Adjusted listing restriction");
        Request previewRequest = request(Outcome.MODIFIED, fixture, "rollback-command",
                replacement, false, null);
        var preview = tx(() -> service.preview(fixture.appealId(), previewRequest));

        EnforcementService failingEnforcement = new EnforcementService(repository,
                mock(CurrentActorProvider.class), mock(AuthServiceClient.class), new UlidGenerator()) {
            @Override
            EnforcementContracts.Result createTrusted(EnforcementContracts.CreateCommand raw,
                    TrustedAdminActor actor, String parentActionId) {
                throw new EnforcementExceptions.Conflict("Injected replacement failure.");
            }
        };
        ListingAppealResolutionService failing = new ListingAppealResolutionService(repository,
                failingEnforcement, Clock.fixed(NOW, ZoneOffset.UTC));
        Request execute = request(Outcome.MODIFIED, fixture, "rollback-command", replacement,
                true, preview.confirmationToken());

        assertThatThrownBy(() -> tx(() -> failing.execute(fixture.appealId(), execute)))
                .isInstanceOf(EnforcementExceptions.Conflict.class)
                .hasMessageContaining("Injected replacement failure");
        assertThat(jdbc.queryForObject("select revoked_at is null from enforcement_actions where id = ?",
                Boolean.class, fixture.actionId())).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from enforcement_events
                 where enforcement_action_id = ? and event_type = 'REVOKED'
                """, Integer.class, fixture.actionId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isZero();
    }

    @Test
    void concurrentFinalizationHasOneWinnerAndOneReplacement() throws Exception {
        Fixture fixture = fixture("CON", 7, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY));
        Replacement replacement = new Replacement(ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), Instant.EPOCH, null,
                "APPEAL_ADJUSTED", "Adjusted listing restriction");
        var preview = tx(() -> service.preview(fixture.appealId(),
                request(Outcome.MODIFIED, fixture, null, replacement, false, null)));
        Request first = request(Outcome.MODIFIED, fixture, "concurrent-one", replacement,
                true, preview.confirmationToken());
        Request second = request(Outcome.MODIFIED, fixture, "concurrent-two", replacement,
                true, preview.confirmationToken());

        int successes = 0;
        int conflicts = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.List<Callable<Response>> tasks = java.util.List.of(
                    () -> tx(() -> service.execute(fixture.appealId(), first)),
                    () -> tx(() -> service.execute(fixture.appealId(), second)));
            var futures = executor.invokeAll(tasks);
            for (var future : futures) {
                try {
                    assertThat(future.get().replacementEnforcementActionId()).isNotBlank();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(EnforcementExceptions.Conflict.class);
                    conflicts++;
                }
            }
        }
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from enforcement_actions where parent_enforcement_action_id = ?
                """, Integer.class, fixture.actionId())).isEqualTo(1);
    }

    @Test
    void upheldIsOwnerPinnedRequiresNoReinstatePermissionAndReplaysWithoutMutation() {
        Fixture fixture = fixture("UPH", 8, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY));
        Executor executor = new Executor(EXECUTOR, "Actual executor", Set.of("admin.appeal.resolve"));
        Request previewRequest = new Request(Outcome.UPHELD, fixture.actionId(), 0L,
                fixture.listingVersion(), "APPEAL_UPHELD", "Final appeal resolution", null,
                null, executor, Map.of("origin", "APPEAL_RESOLUTION"), false, null, false);
        Response preview = tx(() -> service.preview(fixture.appealId(), previewRequest));
        Request execute = new Request(Outcome.UPHELD, fixture.actionId(), 0L,
                fixture.listingVersion(), "APPEAL_UPHELD", "Final appeal resolution", null,
                "uphold-command", executor, Map.of("origin", "APPEAL_RESOLUTION"), true,
                preview.confirmationToken(), false);

        Response result = tx(() -> service.execute(fixture.appealId(), execute));
        Response replay = tx(() -> service.execute(fixture.appealId(), new Request(
                execute.outcome(), execute.enforcementActionId(), execute.expectedEnforcementVersion(),
                execute.expectedTargetVersion(), execute.reasonCode(), execute.reason(), execute.replacement(),
                execute.idempotencyKey(), execute.executor(), execute.safeMetadata(), true,
                execute.confirmationToken(), true)));

        assertThat(result.currentEffectiveEnforcementState()).isEqualTo(EffectiveState.SUSPENDED);
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbc.queryForObject("select revoked_at is null from enforcement_actions where id = ?",
                Boolean.class, fixture.actionId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isEqualTo(1);
    }

    @Test
    void upheldConfirmationRejectsAnOverlappingActionCreatedAfterPreview() {
        Fixture fixture = fixture("UPS", 9, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY));
        Request previewRequest = request(Outcome.UPHELD, fixture, null, null, false, null);
        Response preview = tx(() -> service.preview(fixture.appealId(), previewRequest));
        insertAction(fixture.listingId(), fixture.weakerActionId(), ActionType.RESTRICT,
                Set.of(Scope.LISTING_PURCHASABILITY), "uphold-overlap");

        Request execute = request(Outcome.UPHELD, fixture, "uphold-stale", null,
                true, preview.confirmationToken());
        assertThatThrownBy(() -> tx(() -> service.execute(fixture.appealId(), execute)))
                .isInstanceOf(EnforcementExceptions.Conflict.class)
                .hasMessageContaining("changed after the resolution preview");
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isZero();
    }

    @Test
    void recoveryOnlyNeverStartsANewOwnerMutation() {
        Fixture fixture = fixture("RCV", 10, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY));
        Request previewRequest = request(Outcome.REVOKED, fixture, null, null, false, null);
        Response preview = tx(() -> service.preview(fixture.appealId(), previewRequest));
        Request recovery = new Request(Outcome.REVOKED, fixture.actionId(), 0L,
                fixture.listingVersion(), "APPEAL_REVOKED", "Final appeal resolution", null,
                "recovery-without-owner-result", previewRequest.executor(), previewRequest.safeMetadata(),
                true, preview.confirmationToken(), true);

        assertThatThrownBy(() -> tx(() -> service.execute(fixture.appealId(), recovery)))
                .isInstanceOf(EnforcementExceptions.Conflict.class)
                .hasMessageContaining("No completed listing appeal resolution exists");
        assertThat(jdbc.queryForObject("select revoked_at is null from enforcement_actions where id = ?",
                Boolean.class, fixture.actionId())).isTrue();
    }

    @Test
    void expiredReplacementAtFirstExecutionLeavesOriginalAndCommandUntouched() {
        Fixture fixture = fixture("EXP", 11, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY));
        Instant expiresAt = Instant.now().plusSeconds(600);
        MutableClock clock = new MutableClock(expiresAt.minusSeconds(30));
        ListingAppealResolutionService advancingService = resolutionService(clock);
        Replacement replacement = new Replacement(ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), Instant.EPOCH, expiresAt,
                "APPEAL_ADJUSTED", "Adjusted listing restriction");
        Request previewRequest = request(Outcome.MODIFIED, fixture, "expired-new-command",
                replacement, false, null);
        Response preview = tx(() -> advancingService.preview(fixture.appealId(), previewRequest));
        clock.advanceTo(expiresAt);
        Request execute = request(Outcome.MODIFIED, fixture, "expired-new-command",
                replacement, true, preview.confirmationToken());

        assertThatThrownBy(() -> tx(() -> advancingService.execute(fixture.appealId(), execute)))
                .isInstanceOf(EnforcementExceptions.Validation.class)
                .hasMessageContaining("expired at execution time");

        assertThat(jdbc.queryForObject("select revoked_at is null from enforcement_actions where id = ?",
                Boolean.class, fixture.actionId())).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*) from enforcement_events
                 where enforcement_action_id = ? and event_type = 'REVOKED'
                """, Integer.class, fixture.actionId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from enforcement_actions where parent_enforcement_action_id = ?
                """, Integer.class, fixture.actionId())).isZero();
    }

    @Test
    void recoveryOnlyReplaysCompletedModifiedResolutionAfterReplacementExpires() {
        Fixture fixture = fixture("RXP", 12, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY));
        Instant startedAt = Instant.now();
        Instant expiresAt = startedAt.plusSeconds(600);
        MutableClock clock = new MutableClock(startedAt);
        ListingAppealResolutionService advancingService = resolutionService(clock);
        Replacement replacement = new Replacement(ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), Instant.EPOCH, expiresAt,
                "APPEAL_ADJUSTED", "Adjusted listing restriction");
        Request previewRequest = request(Outcome.MODIFIED, fixture, "expired-replay-command",
                replacement, false, null);
        Response preview = tx(() -> advancingService.preview(fixture.appealId(), previewRequest));
        Request execute = request(Outcome.MODIFIED, fixture, "expired-replay-command",
                replacement, true, preview.confirmationToken());
        Response completed = tx(() -> advancingService.execute(fixture.appealId(), execute));

        clock.advanceTo(expiresAt);
        Request recovery = new Request(execute.outcome(), execute.enforcementActionId(),
                execute.expectedEnforcementVersion(), execute.expectedTargetVersion(), execute.reasonCode(),
                execute.reason(), execute.replacement(), execute.idempotencyKey(), execute.executor(),
                execute.safeMetadata(), execute.confirmed(), execute.confirmationToken(), true);
        Response replay = tx(() -> advancingService.execute(fixture.appealId(), recovery));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replacementEnforcementActionId())
                .isEqualTo(completed.replacementEnforcementActionId());
        assertThat(jdbc.queryForObject("select count(*) from listing_appeal_resolution_commands where appeal_id = ?",
                Integer.class, fixture.appealId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from enforcement_actions where parent_enforcement_action_id = ?
                """, Integer.class, fixture.actionId())).isEqualTo(1);
    }

    private static Request request(Outcome outcome, Fixture fixture, String key, Replacement replacement,
            boolean confirmed, String token) {
        return new Request(outcome, fixture.actionId(), 0L, fixture.listingVersion(),
                outcome == Outcome.MODIFIED ? "APPEAL_MODIFIED" : "APPEAL_REVOKED",
                "Final appeal resolution", replacement, key,
                new Executor(EXECUTOR, "Actual executor", Set.of("admin.appeal.resolve",
                        AdminPermission.LISTING_REINSTATE.id(), AdminPermission.LISTING_SUSPEND.id())),
                Map.of("origin", "APPEAL_RESOLUTION"), confirmed, token, false);
    }

    private static Fixture fixture(String suffix, long listingVersion, ActionType actionType, Set<Scope> scopes) {
        String prefix = switch (suffix) {
            case "MOD" -> "8B";
            case "REV" -> "8C";
            case "PER" -> "8D";
            case "ROL" -> "8E";
            case "CON" -> "8F";
            case "UPH" -> "90";
            case "UPS" -> "91";
            case "EXP" -> "93";
            case "RXP" -> "94";
            default -> "92";
        };
        String listingId = "01ARZ3NDEKTSV4RRFFQ69G" + prefix + "A0";
        String actionId = "01ARZ3NDEKTSV4RRFFQ69G" + prefix + "B0";
        String appealId = "01ARZ3NDEKTSV4RRFFQ69G" + prefix + "C0";
        String sellerId = "01ARZ3NDEKTSV4RRFFQ69G" + prefix + "D0";
        String weakerId = "01ARZ3NDEKTSV4RRFFQ69G" + prefix + "E0";
        jdbc.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, category_id, title, description,
                    condition_code, price_amount, currency, negotiable, quantity, public_city,
                    public_region, status, moderation_status, version, created_at, updated_at
                ) values (?, 'INDIVIDUAL', ?, ?, ?, 'Appeal listing', 'GOOD', ?, 'USD', true,
                    1, 'City', 'Region', 'ACTIVE', 'APPROVED', ?, ?, ?)
                """, listingId, sellerId, CATEGORY, "Appeal " + suffix, BigDecimal.TEN,
                listingVersion, Timestamp.from(NOW), Timestamp.from(NOW));
        insertAction(listingId, actionId, actionType, scopes, "original-" + suffix.toLowerCase());
        return new Fixture(suffix, listingId, actionId, appealId, weakerId, listingVersion);
    }

    private static void insertAction(String listingId, String actionId, ActionType type,
            Set<Scope> scopes, String key) {
        repository.insert(new EnforcementRepository.Action(actionId, listingId, type, scopes, 0,
                Instant.EPOCH, null, "POLICY", "Original enforcement", null, 0,
                "01ARZ3NDEKTSV4RRFFQ69G8AAF", "Recommendation author", NOW.minusSeconds(60), null,
                "original-correlation", actionId, key, "a".repeat(64), Map.of()));
        repository.event(new EnforcementRepository.Event(actionId.substring(0, 25) + "F", actionId,
                "CREATED", null, "ACTIVE", NOW.minusSeconds(60),
                "01ARZ3NDEKTSV4RRFFQ69G8AAF", "Recommendation author", "POLICY",
                "Original enforcement", "original-correlation", actionId.substring(0, 25) + "G", Map.of()));
    }

    private static <T> T tx(java.util.function.Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private static ListingAppealResolutionService resolutionService(Clock clock) {
        EnforcementService enforcement = new EnforcementService(repository, mock(CurrentActorProvider.class),
                mock(AuthServiceClient.class), new UlidGenerator());
        return new ListingAppealResolutionService(repository, enforcement, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceTo(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private record Fixture(String suffix, String listingId, String actionId, String appealId,
            String weakerActionId, long listingVersion) { }
}
