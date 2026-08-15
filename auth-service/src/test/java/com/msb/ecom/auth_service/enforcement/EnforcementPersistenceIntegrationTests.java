package com.msb.ecom.auth_service.enforcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Source;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class EnforcementPersistenceIntegrationTests {
    @Container
    static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");
    static EnforcementRepository repository;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").target("202608120100").load().migrate();
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new EnforcementRepository(jdbc, new ObjectMapper());
    }

    @Test
    void persistsActionScopesEventAndDurableIdempotencyAtomicallyShaped() {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        String actionId = "01ARZ3NDEKTSV4RRFFQ69G5FAA";
        String key = "auth-enforcement-1";
        assertThat(repository.reserveIdempotency("CREATE", key, "a".repeat(64), now)).isTrue();
        repository.insertAction(new EnforcementRepository.Action(actionId, TargetType.USER,
                "01ARZ3NDEKTSV4RRFFQ69G5FAB", ActionType.SUSPEND,
                Set.of(Scope.USER_LOGIN, Scope.USER_SELLING), 0, now, now.plusSeconds(3600),
                "POLICY_ABUSE", "Human reason", "01ARZ3NDEKTSV4RRFFQ69G5FAC", 4,
                Source.HUMAN_ADMIN, "01ARZ3NDEKTSV4RRFFQ69G5FAD", "Safety Admin", now, null,
                "correlation-1", "01ARZ3NDEKTSV4RRFFQ69G5FAE", key, "a".repeat(64), null, null,
                Map.of("policyReference", "POL-42")));
        repository.insertEvent(new EnforcementRepository.Event("01ARZ3NDEKTSV4RRFFQ69G5FAF", actionId,
                "CREATED", null, "ACTIVE", now, "PLATFORM_ADMIN", "01ARZ3NDEKTSV4RRFFQ69G5FAD",
                "Safety Admin", Source.HUMAN_ADMIN, "POLICY_ABUSE", "Human reason", "correlation-1",
                "01ARZ3NDEKTSV4RRFFQ69G5FAG", Map.of("policyReference", "POL-42")));
        repository.completeIdempotency("CREATE", key, actionId, now);

        EnforcementRepository.Action stored = repository.findAction(actionId, false).orElseThrow();
        assertThat(stored.scopes()).containsExactlyInAnyOrder(Scope.USER_LOGIN, Scope.USER_SELLING);
        assertThat(stored.safeMetadata()).containsEntry("policyReference", "POL-42");
        assertThat(repository.timeline(TargetType.USER, stored.targetId())).hasSize(1);
        assertThat(repository.reserveIdempotency("CREATE", key, "a".repeat(64), now)).isFalse();
        assertThatThrownBy(() -> jdbc.update("delete from enforcement_actions where id = ?", actionId))
                .isInstanceOf(Exception.class);
    }

    @Test
    void concurrentIdenticalReservationsCreateOneDurableCommandSlot() throws Exception {
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> (Callable<Boolean>) () -> repository.reserveIdempotency(
                            "CREATE", "concurrent-auth-enforcement", "c".repeat(64), now))
                    .toList();
            long winners = executor.invokeAll(tasks).stream().filter(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).count();
            assertThat(winners).isEqualTo(1);
        }
    }
}
