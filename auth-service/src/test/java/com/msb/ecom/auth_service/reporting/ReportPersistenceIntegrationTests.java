package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.reporting.ReportContracts.ReasonCode;
import com.msb.ecom.auth_service.reporting.ReportContracts.Severity;
import com.msb.ecom.auth_service.reporting.ReportContracts.Status;
import com.msb.ecom.auth_service.reporting.ReportContracts.TargetType;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ReportPersistenceIntegrationTests {
    @Container static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");
    static ReportRepository repository;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        repository = new ReportRepository(new JdbcTemplate(dataSource), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void cleanMigrationPersistsImmutableSnapshotEventAndNoCrossTargetForeignKey() {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        String reportId = "01ARZ3NDEKTSV4RRFFQ69G5FBA";
        repository.insert(row(reportId, now));
        assertThat(repository.reserveDuplicate("01ARZ3NDEKTSV4RRFFQ69G5FBB", TargetType.LISTING,
                "01ARZ3NDEKTSV4RRFFQ69G5FBC", ReasonCode.SCAM, reportId, now, now.plusSeconds(86400))).isTrue();
        repository.insertEvent(new ReportRepository.EventRow("01ARZ3NDEKTSV4RRFFQ69G5FBD", reportId,
                "REPORT_SUBMITTED", now, "MARKETPLACE_USER", "01ARZ3NDEKTSV4RRFFQ69G5FBB",
                "Reporter", "MARKETPLACE", null, "SUBMITTED", "SCAM", "Evidence", "corr",
                "01ARZ3NDEKTSV4RRFFQ69G5FBE", Map.of()));

        var stored = repository.find(reportId).orElseThrow();
        assertThat(stored.targetSnapshot().get("title").asText()).isEqualTo("Title A");
        assertThat(repository.events(reportId)).hasSize(1);
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        Instant now = Instant.parse("2026-08-15T01:00:00Z");
        String reportId = "01ARZ3NDEKTSV4RRFFQ69G5FBF";
        repository.insert(row(reportId, now));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.<Callable<Boolean>>of(
                    () -> repository.claim(reportId, 0, "01ARZ3NDEKTSV4RRFFQ69G5FBG", now),
                    () -> repository.claim(reportId, 0, "01ARZ3NDEKTSV4RRFFQ69G5FBH", now));
            long winners = executor.invokeAll(tasks).stream().filter(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).count();
            assertThat(winners).isEqualTo(1);
        }
        assertThat(repository.find(reportId).orElseThrow().version()).isEqualTo(1);
    }

    private static ReportRepository.ReportRow row(String id, Instant now) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new ReportRepository.ReportRow(id, "01ARZ3NDEKTSV4RRFFQ69G5FBB", TargetType.LISTING,
                "01ARZ3NDEKTSV4RRFFQ69G5FBC", "Title A", ReasonCode.SCAM, "Evidence", Severity.MEDIUM,
                Status.SUBMITTED, null, mapper.valueToTree(Map.of("listingId", "01ARZ3NDEKTSV4RRFFQ69G5FBC",
                "title", "Title A", "capturedAt", now.toString())), Map.of(), 0, null, null, null, null,
                "corr", now, now);
    }
}
