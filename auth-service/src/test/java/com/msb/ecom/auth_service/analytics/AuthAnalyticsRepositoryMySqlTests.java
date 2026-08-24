package com.msb.ecom.auth_service.analytics;

import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.Granularity;
import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.TrendMetric;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AuthAnalyticsRepositoryMySqlTests {
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-08T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    @Container static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");
    static JdbcTemplate jdbc;
    static AuthAnalyticsRepository repository;

    @BeforeAll
    static void migrateAndSeed() {
        String baseJdbcUrl = mysql.getJdbcUrl();
        String jdbcUrl = baseJdbcUrl + (baseJdbcUrl.contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        Flyway.configure().dataSource(jdbcUrl, mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, mysql.getUsername(),
                mysql.getPassword()));
        repository = new AuthAnalyticsRepository(jdbc);
        user(id(1), "fixture-admin", FROM.minusSeconds(86400));
        user(id(2), "included-user", FROM);
        user(id(3), "excluded-user", TO);
        reports();
        cases();
        enforcementAndAppeals();
        support();
    }

    @Test
    void usesExactHalfOpenBoundariesAndAuthoritativeLifecycleTimes() {
        var window = repository.window(FROM, TO);

        assertThat(window.marketplace().newUsers()).isEqualTo(1);
        assertThat(window.trust().reportsSubmitted()).isEqualTo(1);
        assertThat(window.trust().reportsDismissed()).isEqualTo(1);
        assertThat(window.trust().casesOpened()).isEqualTo(1);
        assertThat(window.trust().casesClosedActioned()).isEqualTo(1);
        assertThat(window.trust().enforcementCreated()).isEqualTo(1);
        assertThat(window.trust().appealsSubmitted()).isEqualTo(5);
        assertThat(window.trust().appealsUpheld()).isEqualTo(2);
        assertThat(window.trust().appealsModified()).isEqualTo(1);
        assertThat(window.trust().appealsRevoked()).isEqualTo(1);
        long finalized = window.trust().appealsUpheld() + window.trust().appealsModified()
                + window.trust().appealsRevoked();
        assertThat(finalized).isEqualTo(4);
        assertThat((window.trust().appealsModified() + window.trust().appealsRevoked())
                * 100 / finalized).isEqualTo(50);
        assertThat(jdbc.queryForObject(
                "select count(*) from appeals where status='MODIFY_RECOMMENDED'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from appeals where status='SUBMITTED'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from appeals where resolved_at=?", Long.class,
                Timestamp.from(FROM))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from appeals where resolved_at=?", Long.class,
                Timestamp.from(TO))).isEqualTo(1);
        assertThat(window.support().created()).isEqualTo(1);
        assertThat(window.support().resolved()).isEqualTo(1);
        assertThat(window.support().averageFirstResponseSeconds()).isEqualByComparingTo("120.0000");
        assertThat(window.support().averageResolutionSeconds()).isEqualByComparingTo("3600.0000");
    }

    @Test
    void currentBacklogIsAsOfGenerationTimeAndTrendExcludesToBoundary() {
        var backlog = repository.backlog(NOW);
        var trend = repository.trend(TrendMetric.REPORTS_SUBMITTED, FROM, TO, Granularity.DAY);

        // Backlog is an as-of-now snapshot, so the record exactly at the range's `to`
        // boundary is correctly excluded from the flow but remains in the current queue.
        assertThat(backlog.trust().unresolvedReports()).isEqualTo(2);
        assertThat(backlog.trust().unassignedReports()).isEqualTo(2);
        assertThat(backlog.trust().openCases()).isEqualTo(1);
        assertThat(backlog.support().open()).isEqualTo(2);
        assertThat(backlog.support().unassigned()).isEqualTo(2);
        assertThat(trend).hasSize(1);
        assertThat(trend.getFirst().value()).isEqualTo(1);
        assertThat(trend.getFirst().bucketStart()).isEqualTo(FROM);
        assertThat(repository.enforcementByTargetAndScope(FROM, TO, NOW))
                .singleElement().satisfies(scope -> {
                    assertThat(scope.key()).isEqualTo("USER:MARKETPLACE_ACCESS");
                    assertThat(scope.createdInRange()).isEqualTo(1);
                    assertThat(scope.currentActive()).isEqualTo(1);
                });
    }

    private static void reports() {
        report(id(10), "SUBMITTED", FROM);
        report(id(11), "DISMISSED", FROM.minusSeconds(3600));
        report(id(12), "SUBMITTED", TO);
        report(id(13), "LINKED_TO_CASE", FROM.minusSeconds(7200));
        jdbc.update("""
                insert into report_events(event_id,report_id,event_type,occurred_at,actor_type,actor_id,
                  actor_display_name,source,previous_state,new_state,reason_code,reason,correlation_id,
                  request_id,safe_metadata)
                values(?,?, 'REPORT_DISMISSED',?,'PLATFORM_ADMIN',?,'Admin','HUMAN_ADMIN',
                  'UNDER_TRIAGE','DISMISSED','NO_VIOLATION','No violation','correlation',?,json_object())
                """, id(20), id(11), Timestamp.from(FROM.plusSeconds(60)), id(1), id(21));
    }

    private static void report(String reportId, String status, Instant created) {
        jdbc.update("""
                insert into reports(id,reporter_user_id,target_type,target_id,safe_target_label,
                  reason_code,description,severity,status,assigned_admin_id,target_snapshot,
                  evidence_metadata,version,triaged_at,triaged_by,disposition_reason_code,
                  disposition_reason,correlation_id,created_at,updated_at)
                values(?,?,'USER',?,'User','OTHER',null,'LOW',?,null,json_object(),json_object(),0,
                  null,null,null,null,'correlation',?,?)
                """, reportId, id(2), id(3), status, Timestamp.from(created), Timestamp.from(created));
    }

    private static void cases() {
        caseRow(id(30), "OPEN", FROM, null);
        caseRow(id(31), "CLOSED_ACTIONED", FROM.minusSeconds(7200), FROM.plusSeconds(1800));
    }

    private static void caseRow(String caseId, String status, Instant created, Instant closed) {
        jdbc.update("""
                insert into investigation_cases(id,title,status,severity,primary_target_type,
                  primary_target_id,safe_primary_target_label,assigned_admin_id,created_by_admin_id,
                  conclusion_code,conclusion_reason,ready_for_action_at,closed_at,correlation_id,
                  version,created_at,updated_at)
                values(?,'Fixture case',?,'LOW','USER',?,'User',null,?,null,null,null,?,
                  'correlation',0,?,?)
                """, caseId, status, id(3), id(1), timestamp(closed), Timestamp.from(created),
                Timestamp.from(closed == null ? created : closed));
    }

    private static void enforcementAndAppeals() {
        enforcement(id(40), FROM, null);
        appeal(id(50), id(40), "MODIFY_RECOMMENDED", "MODIFY_RECOMMENDED",
                FROM.plusSeconds(100), FROM.plusSeconds(300));

        enforcement(id(41), FROM.minusSeconds(7200), FROM.minusSeconds(3600));
        appeal(id(51), id(41), "SUBMITTED", null,
                FROM.plusSeconds(400), null);

        enforcement(id(42), FROM.minusSeconds(7200), FROM.minusSeconds(3600));
        appeal(id(52), id(42), "UPHELD", "UPHOLD_RECOMMENDED",
                FROM.minusSeconds(120), FROM.minusSeconds(60));
        // The lower boundary is inclusive for final outcomes.
        finalizeAppeal(id(52), "UPHELD", FROM, null);

        enforcement(id(43), FROM.minusSeconds(7200), FROM.minusSeconds(3600));
        appeal(id(53), id(43), "UPHELD", "UPHOLD_RECOMMENDED",
                FROM.plusSeconds(800), FROM.plusSeconds(900));
        finalizeAppeal(id(53), "UPHELD", FROM.plusSeconds(1000), null);

        enforcement(id(44), FROM.minusSeconds(7200), FROM.minusSeconds(3600));
        appeal(id(54), id(44), "MODIFIED", "MODIFY_RECOMMENDED",
                FROM.plusSeconds(1100), FROM.plusSeconds(1200));
        finalizeAppeal(id(54), "MODIFIED", FROM.plusSeconds(1300), id(46));

        enforcement(id(45), FROM.minusSeconds(7200), FROM.minusSeconds(3600));
        appeal(id(55), id(45), "REVOKED", "REVOKE_RECOMMENDED",
                FROM.plusSeconds(1400), FROM.plusSeconds(1500));
        finalizeAppeal(id(55), "REVOKED", FROM.plusSeconds(1600), null);

        enforcement(id(47), FROM.minusSeconds(7200), FROM.minusSeconds(3600));
        appeal(id(56), id(47), "REVOKE_RECOMMENDED", "REVOKE_RECOMMENDED",
                FROM.minusSeconds(120), FROM.minusSeconds(60));
        // The upper boundary is exclusive and must not inflate the exact 2/1/1 fixture.
        finalizeAppeal(id(56), "REVOKED", TO, null);
    }

    private static void enforcement(String enforcementId, Instant created, Instant revoked) {
        jdbc.update("""
                insert into enforcement_actions(id,target_type,target_id,action_type,version,
                  effective_at,expires_at,reason_code,reason,case_id,parent_enforcement_action_id,
                  target_version_at_decision,source,created_by_actor_id,created_by_actor_display_name,
                  created_at,revoked_at,revoked_by_actor_id,revoked_by_actor_display_name,
                  revoke_reason_code,revoke_reason,correlation_id,request_id,idempotency_key,
                  command_fingerprint,revoke_idempotency_key,revoke_command_fingerprint,safe_metadata)
                values(?,'USER',?,'RESTRICT',0,?,null,'POLICY_VIOLATION','Fixture',null,null,0,
                  'HUMAN_ADMIN',?,'Admin',?,?,?,?,null,null,'correlation',?,? ,?,null,null,json_object())
                """, enforcementId, id(3), Timestamp.from(created), id(1), Timestamp.from(created),
                timestamp(revoked), revoked == null ? null : id(1),
                revoked == null ? null : "Admin", id(Integer.parseInt(enforcementId.substring(24)) + 100),
                "analytics-enforcement-" + enforcementId, "0".repeat(64));
        jdbc.update("insert into enforcement_action_scopes(enforcement_action_id,scope) values (?,?)",
                enforcementId, "MARKETPLACE_ACCESS");
    }

    private static void appeal(String appealId, String enforcementId, String status,
            String reviewOutcome, Instant submitted, Instant reviewed) {
        jdbc.update("""
                insert into appeals(id,enforcement_action_id,target_type,target_id,safe_target_label,
                  appellant_type,appellant_user_id,status,reason_code,explanation,safe_evidence_references,
                  submitted_at,assigned_admin_id,review_started_at,reviewed_at,review_outcome,
                  review_reason_code,review_reason,replacement_action_type,replacement_expires_at,
                  replacement_reason_code,replacement_reason,replacement_expected_target_version,
                  original_case_id,original_enforcement_version,correlation_id,version,created_at,updated_at)
                values(?,?,'USER',?,'User','USER',?,?,'ACTION_TOO_SEVERE',null,
                  json_array(),?,? ,?,?,?,'ACTION_TOO_SEVERE','Recommendation',null,null,null,null,null,
                  null,0,'correlation',0,?,?)
                """, appealId, enforcementId, id(3), id(3), status, Timestamp.from(submitted),
                reviewed == null ? null : id(1), timestamp(reviewed), timestamp(reviewed), reviewOutcome,
                Timestamp.from(submitted), Timestamp.from(reviewed == null ? submitted : reviewed));
    }

    private static void finalizeAppeal(String appealId, String status, Instant resolved,
            String replacementEnforcementId) {
        jdbc.update("""
                update appeals
                set status=?, resolved_at=?, resolved_by_admin_id=?,
                    resolved_by_admin_display_name='Analytics resolver',
                    resolution_summary=?, resolution_idempotency_key=?,
                    resolution_request_hash=?, replacement_enforcement_action_id=?,
                    resolution_original_enforcement_version=0, resolution_target_version=0,
                    updated_at=?
                where id=?
                """, status, Timestamp.from(resolved), id(1), "Final " + status.toLowerCase(),
                "analytics-resolution-" + appealId, "1".repeat(64), replacementEnforcementId,
                Timestamp.from(resolved), appealId);
    }

    private static void support() {
        ticket(id(60), "OPEN", FROM, null);
        ticket(id(61), "RESOLVED", FROM.minusSeconds(3600), FROM);
        ticket(id(62), "OPEN", TO, null);
        jdbc.update("""
                insert into support_messages(id,ticket_id,author_type,author_user_id,body,
                  idempotency_key,request_hash,created_at)
                values(?,?,'SUPPORT_ADMIN',?,'Response','analytics-response',?,?)
                """, id(63), id(60), id(1), "1".repeat(64), Timestamp.from(FROM.plusSeconds(120)));
    }

    private static void ticket(String ticketId, String status, Instant created, Instant resolved) {
        jdbc.update("""
                insert into support_tickets(id,requester_user_id,category,subject,description,status,
                  priority,assigned_admin_id,resolved_at,resolution_code,resolution_reason,
                  duplicate_fingerprint,correlation_id,version,created_at,updated_at)
                values(?,?,'ACCOUNT_HELP','Fixture','Fixture support description',?,'MEDIUM',null,
                  ?,null,null,?,'correlation',0,?,?)
                """, ticketId, id(2), status, timestamp(resolved), "fingerprint-" + ticketId,
                Timestamp.from(created), Timestamp.from(resolved == null ? created : resolved));
    }

    private static void user(String userId, String handle, Instant created) {
        jdbc.update("""
                insert into users(id,keycloak_sub,email,email_verified,display_name,public_handle,phone,
                  phone_verified,avatar_url,status,account_type,version,created_at,updated_at)
                values(?,?,?,true,?,?,null,false,null,'ACTIVE','HUMAN',0,?,?)
                """, userId, "keycloak-" + userId, handle + "@example.test", handle, handle,
                Timestamp.from(created), Timestamp.from(created));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String id(int value) {
        return "02" + String.format("%024d", value);
    }
}
