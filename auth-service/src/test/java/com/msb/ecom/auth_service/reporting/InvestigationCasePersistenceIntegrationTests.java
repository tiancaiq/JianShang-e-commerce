package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType.SUSPEND;
import static com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope.LISTING_PUBLIC_VISIBILITY;
import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.RelationshipType;
import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.ProposalStatus;
import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.Status;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class InvestigationCasePersistenceIntegrationTests {
    @Container static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");
    static InvestigationCaseRepository cases;
    static CaseEnforcementRepository proposals;
    static ReportRepository reports;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        cases = new InvestigationCaseRepository(jdbc, mapper);
        proposals = new CaseEnforcementRepository(jdbc, mapper);
        reports = new ReportRepository(jdbc, mapper);
    }

    @Test
    void migrationCreatesSixCaseTablesAndDedicatedPermission() {
        Integer tableCount = jdbc.queryForObject("""
                select count(*) from information_schema.tables where table_schema = database()
                  and table_name in ('investigation_cases', 'investigation_case_reports',
                    'investigation_case_targets', 'investigation_case_notes',
                    'investigation_case_evidence', 'investigation_case_events')
                """, Integer.class);
        Integer permissionCount = jdbc.queryForObject("""
                select count(*) from admin_permissions
                where id = 'admin.report.investigate' and reserved = false
                """, Integer.class);
        assertThat(tableCount).isEqualTo(6);
        assertThat(permissionCount).isEqualTo(1);
    }

    @Test
    void reportLinkUsesBothVersionsAndPreservesAppendOnlyHistory() {
        Instant now = Instant.parse("2026-08-16T00:00:00Z");
        String reportId = "01ARZ3NDEKTSV4RRFFQ69G6AAA";
        String caseId = "01ARZ3NDEKTSV4RRFFQ69G6AAB";
        String adminId = "01ARZ3NDEKTSV4RRFFQ69G6AAC";
        reports.insert(report(reportId, now, ReportContracts.Status.READY_FOR_INVESTIGATION));
        cases.insertCase(caseRow(caseId, now));
        cases.insertTarget(caseId, ReportContracts.TargetType.LISTING,
                "01ARZ3NDEKTSV4RRFFQ69G6AAD", "Listing A", RelationshipType.PRIMARY, adminId, now);
        cases.insertReportLink(caseId, reportId, adminId, now);
        assertThat(cases.markReportLinked(reportId, 0, now)).isTrue();
        cases.insertEvent(new InvestigationCaseRepository.EventRow("01ARZ3NDEKTSV4RRFFQ69G6AAE", caseId,
                "REPORT_LINKED", now, adminId, "Admin", "READY_FOR_INVESTIGATION", "LINKED_TO_CASE",
                null, "Report linked", "corr", "01ARZ3NDEKTSV4RRFFQ69G6AAF", Map.of("reportId", reportId)));

        assertThat(reports.find(reportId).orElseThrow().status()).isEqualTo(ReportContracts.Status.LINKED_TO_CASE);
        assertThat(cases.caseIdForReport(reportId)).contains(caseId);
        assertThat(cases.events(caseId)).extracting(InvestigationCaseRepository.EventRow::eventType)
                .containsExactly("REPORT_LINKED");
    }

    @Test
    void concurrentCaseClaimHasOneWinnerAndTerminalCaseRejectsFurtherTouches() throws Exception {
        Instant now = Instant.parse("2026-08-16T01:00:00Z");
        String caseId = "01ARZ3NDEKTSV4RRFFQ69G6ABA";
        String adminA = "01ARZ3NDEKTSV4RRFFQ69G6ABB";
        String adminB = "01ARZ3NDEKTSV4RRFFQ69G6ABC";
        cases.insertCase(caseRow(caseId, now));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.<Callable<Boolean>>of(
                    () -> cases.claim(caseId, 0, adminA, now),
                    () -> cases.claim(caseId, 0, adminB, now));
            long winners = executor.invokeAll(tasks).stream().filter(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).count();
            assertThat(winners).isEqualTo(1);
        }
        var claimed = cases.find(caseId).orElseThrow();
        assertThat(cases.start(caseId, claimed.version(), claimed.assignedAdminId(), now)).isTrue();
        var started = cases.find(caseId).orElseThrow();
        assertThat(cases.conclude(caseId, started.version(), started.assignedAdminId(),
                Status.CLOSED_NO_ACTION, "INSUFFICIENT_EVIDENCE", "No reliable evidence", now)).isTrue();
        var closed = cases.find(caseId).orElseThrow();
        assertThat(closed.status()).isEqualTo(Status.CLOSED_NO_ACTION);
        assertThat(cases.touchInvestigableOwned(caseId, closed.version(), closed.assignedAdminId(), now)).isFalse();
    }

    @Test
    void concurrentProposalExecutionPinsAndLinksExactlyOneEnforcementAction() throws Exception {
        Instant now = Instant.parse("2026-08-16T02:00:00Z");
        String caseId = "01ARZ3NDEKTSV4RRFFQ69G6ACA";
        String proposalId = "01ARZ3NDEKTSV4RRFFQ69G6ACB";
        String listingId = "01ARZ3NDEKTSV4RRFFQ69G6ACC";
        String adminId = "01ARZ3NDEKTSV4RRFFQ69G6ACD";
        cases.insertCase(caseRow(caseId, now));
        proposals.insert(new CaseEnforcementRepository.ProposalRow(
                proposalId, caseId, ReportContracts.TargetType.LISTING, listingId,
                SUSPEND, Set.of(LISTING_PUBLIC_VISIBILITY), "POLICY_VIOLATION",
                "Concurrent execution regression", now, null, 7,
                ProposalStatus.DRAFT, adminId, now, now, 0,
                null, null, null, null, null, null, null, "corr-proposal-create"));
        assertThat(proposals.validated(
                caseId, proposalId, 0, 7,
                new ObjectMapper().valueToTree(Map.of("allowed", true)), now)).isTrue();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.List.<Callable<String>>of(
                    () -> executeProposalRace(caseId, proposalId, adminId,
                            "case-exec-a", "01ARZ3NDEKTSV4RRFFQ69G6ACE", now),
                    () -> executeProposalRace(caseId, proposalId, adminId,
                            "case-exec-b", "01ARZ3NDEKTSV4RRFFQ69G6ACF", now));
            var results = executor.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            }).filter(java.util.Objects::nonNull).toList();
            assertThat(results).hasSize(1);
        }

        var executed = proposals.find(caseId, proposalId).orElseThrow();
        assertThat(executed.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(executed.version()).isEqualTo(3);
        assertThat(executed.executionIdempotencyKey()).isIn("case-exec-a", "case-exec-b");
        assertThat(proposals.links(caseId)).singleElement().satisfies(link -> {
            assertThat(link.proposalId()).isEqualTo(proposalId);
            assertThat(link.enforcementActionId()).isEqualTo(executed.resultingEnforcementActionId());
            assertThat(link.executedByAdminId()).isEqualTo(adminId);
        });
    }

    private static String executeProposalRace(
            String caseId,
            String proposalId,
            String adminId,
            String key,
            String actionId,
            Instant now) {
        if (!proposals.pinExecution(caseId, proposalId, 1, key, now)) return null;
        var pinned = proposals.find(caseId, proposalId).orElseThrow();
        if (!proposals.executed(caseId, proposalId, pinned.version(), actionId, now)) return null;
        proposals.link(new CaseEnforcementRepository.LinkRow(
                caseId, proposalId, ReportContracts.TargetType.LISTING,
                "01ARZ3NDEKTSV4RRFFQ69G6ACC", actionId, now, adminId, "corr-proposal-execute"));
        return actionId;
    }

    private static InvestigationCaseRepository.CaseRow caseRow(String id, Instant now) {
        return new InvestigationCaseRepository.CaseRow(id, "[E2E] Investigation", Status.OPEN,
                ReportContracts.Severity.HIGH, ReportContracts.TargetType.LISTING,
                "01ARZ3NDEKTSV4RRFFQ69G6AAD", "Listing A", null,
                "01ARZ3NDEKTSV4RRFFQ69G6AAC", null, null, null, null,
                "corr", 0, now, now, 0, 1, 0);
    }

    private static ReportRepository.ReportRow report(String id, Instant now, ReportContracts.Status status) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new ReportRepository.ReportRow(id, "01ARZ3NDEKTSV4RRFFQ69G6AAZ",
                ReportContracts.TargetType.LISTING, "01ARZ3NDEKTSV4RRFFQ69G6AAD", "Listing A",
                ReportContracts.ReasonCode.SCAM, "Evidence", ReportContracts.Severity.HIGH, status, null,
                mapper.valueToTree(Map.of("listingId", "01ARZ3NDEKTSV4RRFFQ69G6AAD", "title", "Listing A",
                        "capturedAt", now.toString())), Map.of(), 0, now, null,
                "REQUIRES_INVESTIGATION", "Needs investigation", "corr", now, now);
    }
}
