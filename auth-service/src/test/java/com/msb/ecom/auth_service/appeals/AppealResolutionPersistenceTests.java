package com.msb.ecom.auth_service.appeals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.appeals.AppealContracts.FinalOutcome;
import com.msb.ecom.auth_service.appeals.AppealContracts.Status;
import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AppealResolutionPersistenceTests {
    @Container
    static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");
    static AppealRepository repository;
    static AppealResolutionCommandService commands;
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new AppealRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        ProxyFactory commandProxy = new ProxyFactory(new AppealResolutionCommandService(jdbc));
        commandProxy.setProxyTargetClass(true);
        commandProxy.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        commands = (AppealResolutionCommandService) commandProxy.getProxy();
    }

    @Test
    void finalizationPersistsExecutorLinksAndMakesTheVersionedStateImmutable() {
        String appealId = "01ARZ3NDEKTSV4RRFFQ69G6AAA";
        insertRecommended(appealId, "01ARZ3NDEKTSV4RRFFQ69G6AAB", "REVOKE_RECOMMENDED", 5);
        String caseId = "01ARZ3NDEKTSV4RRFFQ69G6AAT";
        jdbc.update("""
                insert into investigation_cases (
                    id, title, status, severity, primary_target_type, primary_target_id,
                    safe_primary_target_label, created_by_admin_id, conclusion_code, conclusion_reason,
                    closed_at, correlation_id, version, created_at, updated_at
                ) values (?, 'Historical case', 'CLOSED_ACTIONED', 'HIGH', 'USER', ?,
                    'Affected user', ?, 'ACTION_TAKEN', 'Original investigation conclusion',
                    current_timestamp(6), 'mysql-case', 7, current_timestamp(6), current_timestamp(6))
                """, caseId, "01ARZ3NDEKTSV4RRFFQ69G6AAZ", "01ARZ3NDEKTSV4RRFFQ69G6AAC");
        jdbc.update("update appeals set original_case_id=? where id=?", caseId, appealId);
        Instant resolvedAt = Instant.parse("2026-08-26T01:00:00Z");

        assertThat(repository.finalizeResolution(appealId, 5, Status.REVOKE_RECOMMENDED,
                FinalOutcome.REVOKED, "01ARZ3NDEKTSV4RRFFQ69G6AAC", "Resolution admin",
                "The appealed enforcement was revoked.", "mysql-resolution-key", "a".repeat(64),
                null, 2, 7, resolvedAt)).isTrue();
        assertThat(repository.finalizeResolution(appealId, 5, Status.REVOKE_RECOMMENDED,
                FinalOutcome.REVOKED, "01ARZ3NDEKTSV4RRFFQ69G6AAD", "Second admin",
                "Second resolution", "mysql-resolution-key-2", "b".repeat(64),
                null, 2, 7, resolvedAt.plusSeconds(1))).isFalse();

        AppealRepository.AppealRow stored = repository.byId(appealId, false).orElseThrow();
        assertThat(stored.status()).isEqualTo(Status.REVOKED);
        assertThat(stored.resolvedAt()).isEqualTo(resolvedAt);
        assertThat(stored.resolvedByAdminName()).isEqualTo("Resolution admin");
        assertThat(stored.resolutionIdempotencyKey()).isEqualTo("mysql-resolution-key");
        assertThat(stored.resolutionOriginalEnforcementVersion()).isEqualTo(2);
        assertThat(stored.resolutionTargetVersion()).isEqualTo(7);
        assertThat(jdbc.queryForMap("""
                select status, conclusion_code, conclusion_reason, version
                from investigation_cases where id=?
                """, caseId)).containsEntry("status", "CLOSED_ACTIONED")
                .containsEntry("conclusion_code", "ACTION_TAKEN")
                .containsEntry("conclusion_reason", "Original investigation conclusion")
                .containsEntry("version", 7L);
    }

    @Test
    void concurrentFinalizationHasOneWinnerAndIdempotencyKeysAreGloballyUnique() throws Exception {
        String appealId = "01ARZ3NDEKTSV4RRFFQ69G6AAE";
        insertRecommended(appealId, "01ARZ3NDEKTSV4RRFFQ69G6AAF", "UPHOLD_RECOMMENDED", 3);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.<Callable<Boolean>>of(
                    () -> repository.finalizeResolution(appealId, 3, Status.UPHOLD_RECOMMENDED,
                            FinalOutcome.UPHELD, "01ARZ3NDEKTSV4RRFFQ69G6AAG", "Admin one", "Upheld",
                            "winner-key-one", "c".repeat(64), null, 1, 4, Instant.now()),
                    () -> repository.finalizeResolution(appealId, 3, Status.UPHOLD_RECOMMENDED,
                            FinalOutcome.UPHELD, "01ARZ3NDEKTSV4RRFFQ69G6AAH", "Admin two", "Upheld",
                            "winner-key-two", "d".repeat(64), null, 1, 4, Instant.now()));
            long winners = executor.invokeAll(tasks).stream().filter(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).count();
            assertThat(winners).isEqualTo(1);
        }

        String otherAppeal = "01ARZ3NDEKTSV4RRFFQ69G6AAJ";
        insertRecommended(otherAppeal, "01ARZ3NDEKTSV4RRFFQ69G6AAK", "UPHOLD_RECOMMENDED", 1);
        String usedKey = repository.byId(appealId, false).orElseThrow().resolutionIdempotencyKey();
        assertThat(repository.finalizeResolution(otherAppeal, 1, Status.UPHOLD_RECOMMENDED,
                FinalOutcome.UPHELD, "01ARZ3NDEKTSV4RRFFQ69G6AAM", "Another admin", "Upheld",
                usedKey, "e".repeat(64), null, 1, 1, Instant.now())).isFalse();
    }

    @Test
    void previewPersistsStateFingerprintAndExecutorBinding() {
        String appealId = "01ARZ3NDEKTSV4RRFFQ69G6AAN";
        String actorId = "01ARZ3NDEKTSV4RRFFQ69G6AAP";
        String token = "01ARZ3NDEKTSV4RRFFQ69G6AAQ";
        insertRecommended(appealId, "01ARZ3NDEKTSV4RRFFQ69G6AAR", "MODIFY_RECOMMENDED", 4);
        Instant now = Instant.parse("2026-08-26T02:00:00Z");
        AppealRepository.ResolutionPreviewRow row = new AppealRepository.ResolutionPreviewRow(
                token, appealId, actorId, "f".repeat(64), "owner-confirmation", now.plusSeconds(300), now);

        assertThat(repository.insertPreview(row)).isTrue();
        assertThat(repository.preview(token, appealId, actorId)).contains(row);
        assertThat(repository.preview(token, appealId, "01ARZ3NDEKTSV4RRFFQ69G6AAS")).isEmpty();
    }

    @Test
    void durableGlobalCommandReservationHasOneOwnerAndDeterministicReplay() throws Exception {
        String key = "global-owner-command-key";
        String firstAppeal = "01ARZ3NDEKTSV4RRFFQ69G6AAV";
        String secondAppeal = "01ARZ3NDEKTSV4RRFFQ69G6AAW";
        String actor = "01ARZ3NDEKTSV4RRFFQ69G6AAX";
        Instant now = Instant.parse("2026-08-26T03:00:00Z");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(java.util.List.<Callable<Boolean>>of(
                    () -> {
                        try {
                            return commands.reserve(key, firstAppeal, "1".repeat(64), actor, now).created();
                        } catch (AppealException exception) {
                            return false;
                        }
                    },
                    () -> {
                        try {
                            commands.reserve(key, secondAppeal, "2".repeat(64), actor, now);
                            return true;
                        } catch (AppealException exception) {
                            return false;
                        }
                    }));
            assertThat(futures.stream().filter(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).count()).isEqualTo(1);
        }

        AppealResolutionCommandService.Reservation stored = commands.find(key).orElseThrow();
        assertThat(stored.appealId()).isIn(firstAppeal, secondAppeal);
        assertThat(jdbc.queryForObject(
                "select count(*) from appeal_resolution_commands where idempotency_key=?",
                Integer.class, key)).isEqualTo(1);
        AppealResolutionCommandService.Reservation replay = commands.reserve(key, stored.appealId(),
                stored.requestHash(), stored.executorAdminId(), now.plusSeconds(1));
        assertThat(replay.created()).isFalse();
    }

    @Test
    void commandReservationSurvivesRollbackOfTheCallingResolutionTransaction() {
        String key = "owner-completed-auth-rollback";
        transactions.executeWithoutResult(status -> {
            commands.reserve(key, "01ARZ3NDEKTSV4RRFFQ69G6AAY", "3".repeat(64),
                    "01ARZ3NDEKTSV4RRFFQ69G6AAZ", Instant.parse("2026-08-26T04:00:00Z"));
            status.setRollbackOnly();
        });

        assertThat(commands.find(key)).isPresent();
    }

    private static void insertRecommended(String appealId, String enforcementId, String status, long version) {
        String outcome = switch (status) {
            case "UPHOLD_RECOMMENDED" -> "UPHOLD_RECOMMENDED";
            case "MODIFY_RECOMMENDED" -> "MODIFY_RECOMMENDED";
            default -> "REVOKE_RECOMMENDED";
        };
        jdbc.update("""
                insert into appeals (
                    id, enforcement_action_id, target_type, target_id, safe_target_label,
                    appellant_type, appellant_user_id, status, reason_code, explanation,
                    safe_evidence_references, submitted_at, reviewed_at, review_outcome,
                    review_reason_code, review_reason, original_enforcement_version,
                    correlation_id, version, created_at, updated_at
                ) values (?, ?, 'USER', ?, 'Affected user', 'USER', ?, ?, 'ACTION_TOO_SEVERE',
                    'Please review', cast('[]' as json), current_timestamp(6), current_timestamp(6), ?,
                    'WRONG_DECISION', 'Recommendation ready for resolution', 1,
                    'mysql-appeal-resolution', ?, current_timestamp(6), current_timestamp(6))
                """, appealId, enforcementId, "01ARZ3NDEKTSV4RRFFQ69G6AAZ",
                "01ARZ3NDEKTSV4RRFFQ69G6AAZ", status, outcome, version);
    }
}
