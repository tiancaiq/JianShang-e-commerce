package com.msb.ecom.auth_service.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class GovernancePersistenceIntegrationTests {
    private static final String REQUESTER = "01GOV000000000000000000001";
    private static final String APPROVER = "01GOV000000000000000000002";
    private static final String APPROVAL_KEY = "rc02-governance-request";
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Container
    static MySQLContainer<?> mysql = MySqlContainerFactory.create("governance_rc");

    static GovernanceRepository repository;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        var dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new GovernanceRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from admin_approval_decisions where approver_admin_id in (?, ?)",
                REQUESTER, APPROVER);
        jdbc.update("delete from admin_approval_requests where requester_admin_id = ?", REQUESTER);
        jdbc.update("delete from users where id in (?, ?)", REQUESTER, APPROVER);
        insertUser(REQUESTER, "rc02-governance-requester");
        insertUser(APPROVER, "rc02-governance-approver");
    }

    @Test
    void concurrentSameKeyApprovalAndDecisionCommandsReserveExactlyOneDurableRow() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<Boolean>) () -> repository.insertApproval(approval(index)))
                    .toList();
            long winners = executor.invokeAll(tasks).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).count();
            assertThat(winners).isEqualTo(1);
        }

        var stored = repository.approvalRequest(
                REQUESTER, "ADMIN_ROLE_GRANT_SUPER_ADMIN", APPROVAL_KEY).orElseThrow();
        assertThat(jdbc.queryForObject("""
                select count(*) from admin_approval_requests
                where requester_admin_id = ? and action_type = ? and request_idempotency_key = ?
                """, Integer.class, REQUESTER, stored.actionType(), APPROVAL_KEY)).isEqualTo(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<Boolean>) () -> repository.insertDecision(
                            new GovernanceRepository.DecisionInsert(id(20 + index), stored.id(), APPROVER,
                                    "APPROVE", "Independent approval", "rc02-governance-decision",
                                    "b".repeat(64), NOW.plusSeconds(index))))
                    .toList();
            long winners = executor.invokeAll(tasks).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).count();
            assertThat(winners).isEqualTo(1);
        }

        assertThat(repository.decisions(stored.id())).hasSize(1);
        assertThat(repository.approvalDecisionCount(stored.id())).isEqualTo(1);
    }

    @Test
    void concurrentExecutionClaimsAllowOneWinnerAndPreserveOptimisticVersion() throws Exception {
        assertThat(repository.insertApproval(approval(1))).isTrue();
        var stored = repository.approvalRequest(
                REQUESTER, "ADMIN_ROLE_GRANT_SUPER_ADMIN", APPROVAL_KEY).orElseThrow();
        jdbc.update("update admin_approval_requests set status = 'APPROVED' where id = ?", stored.id());

        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<Boolean>) () -> repository.startExecution(
                            stored.id(), 0, APPROVER, "rc02-execute-" + index, NOW.plusSeconds(index)))
                    .toList();
            long winners = executor.invokeAll(tasks).stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).count();
            assertThat(winners).isEqualTo(1);
        }

        var executing = repository.approval(stored.id(), false).orElseThrow();
        assertThat(executing.status()).isEqualTo("EXECUTING");
        assertThat(executing.version()).isEqualTo(1);
        assertThat(executing.executionIdempotencyKey()).startsWith("rc02-execute-");
    }

    private GovernanceRepository.ApprovalInsert approval(int suffix) {
        return new GovernanceRepository.ApprovalInsert(
                id(suffix), "ADMIN_ROLE_GRANT_SUPER_ADMIN", REQUESTER,
                "ADMIN", "01GOV000000000000000000099", "a".repeat(64),
                "Grant Super Admin", Map.of("role", "SUPER_ADMIN"), "CRITICAL",
                2, 0, 0L, APPROVAL_KEY, "rc02-correlation", NOW,
                NOW.plusSeconds(3600));
    }

    private void insertUser(String id, String subject) {
        jdbc.update("""
                insert into users(id, keycloak_sub, display_name, public_handle, status, created_at, updated_at)
                values (?, ?, 'RC acceptance admin', ?, 'ACTIVE', ?, ?)
                """, id, subject, subject, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static String id(int value) {
        return "01GOV" + String.format("%021d", value);
    }
}
