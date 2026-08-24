package com.msb.ecom.auth_service.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.Granularity;
import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.TrendMetric;

@Repository
@RequiredArgsConstructor
public class AuthAnalyticsRepository {
    private final JdbcTemplate jdbc;

    public LocalWindow window(Instant from, Instant to) {
        return new LocalWindow(marketplaceWindow(from, to), moderationWindow(from, to),
                trustWindow(from, to), supportWindow(from, to));
    }

    public LocalBacklog backlog(Instant now) {
        return new LocalBacklog(marketplaceBacklog(), moderationBacklog(now), trustBacklog(now),
                supportBacklog(now), governance(now));
    }

    public List<NamedCount> supportCategories(Instant from, Instant to) {
        return jdbc.query("""
                select category as metric_key, category as label, count(*) as metric_value
                from support_tickets
                where created_at >= ? and created_at < ?
                group by category
                order by metric_value desc, category
                limit 10
                """, (rs, row) -> new NamedCount(rs.getString("metric_key"),
                        rs.getString("label"), rs.getLong("metric_value")),
                Timestamp.from(from), Timestamp.from(to));
    }

    public List<NamedCount> enforcementByTargetAndAction(Instant from, Instant to) {
        return jdbc.query("""
                select concat(target_type, ':', action_type) as metric_key,
                       concat(target_type, ' ', action_type) as label,
                       count(*) as metric_value
                from enforcement_actions
                where created_at >= ? and created_at < ?
                group by target_type, action_type
                order by target_type, action_type
                """, (rs, row) -> new NamedCount(rs.getString("metric_key"),
                        rs.getString("label"), rs.getLong("metric_value")),
                Timestamp.from(from), Timestamp.from(to));
    }

    public List<ScopeCount> enforcementByTargetAndScope(Instant from, Instant to, Instant now) {
        return jdbc.query("""
                select concat(ea.target_type, ':', eas.scope) as metric_key,
                       concat(ea.target_type, ' ', eas.scope) as label,
                       sum(case when ea.created_at >= ? and ea.created_at < ? then 1 else 0 end)
                         as created_value,
                       sum(case when ea.revoked_at is null and ea.effective_at <= ?
                         and (ea.expires_at is null or ea.expires_at > ?) then 1 else 0 end)
                         as active_value
                from enforcement_actions ea
                join enforcement_action_scopes eas on eas.enforcement_action_id = ea.id
                where (ea.created_at >= ? and ea.created_at < ?)
                   or (ea.revoked_at is null and ea.effective_at <= ?
                     and (ea.expires_at is null or ea.expires_at > ?))
                group by ea.target_type, eas.scope
                order by ea.target_type, eas.scope
                """, (rs, row) -> new ScopeCount(rs.getString("metric_key"),
                        rs.getString("label"), rs.getLong("created_value"),
                        rs.getLong("active_value")),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(now), Timestamp.from(now));
    }

    public List<TrendBucket> trend(TrendMetric metric, Instant from, Instant to,
            Granularity granularity) {
        String timestampColumn = switch (metric) {
            case REPORTS_SUBMITTED -> "created_at";
            case SUPPORT_TICKETS_CREATED -> "created_at";
            default -> throw new IllegalArgumentException("Trend metric is not owned by Auth Service.");
        };
        String table = metric == TrendMetric.REPORTS_SUBMITTED ? "reports" : "support_tickets";
        String bucket = switch (granularity) {
            case HOUR -> "date_format(" + timestampColumn + ", '%Y-%m-%d %H:00:00')";
            case DAY -> "date_format(" + timestampColumn + ", '%Y-%m-%d 00:00:00')";
            case WEEK -> "date_format(date_sub(date(" + timestampColumn + "), interval weekday("
                    + timestampColumn + ") day), '%Y-%m-%d 00:00:00')";
        };
        String sql = "select " + bucket + " as bucket_start, count(*) as metric_value "
                + "from " + table + " where " + timestampColumn + " >= ? and "
                + timestampColumn + " < ? group by bucket_start order by bucket_start";
        return jdbc.query(sql, (rs, row) -> new TrendBucket(
                        LocalDateTime.parse(rs.getString("bucket_start"),
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                .toInstant(ZoneOffset.UTC),
                        rs.getLong("metric_value")),
                Timestamp.from(from), Timestamp.from(to));
    }

    public MarketplaceWindow marketplaceWindow(Instant from, Instant to) {
        return jdbc.queryForObject("""
                select
                  (select count(*) from users where account_type = 'HUMAN'
                    and created_at >= ? and created_at < ?) as new_users,
                  (select count(*) from businesses
                    where created_at >= ? and created_at < ?) as new_businesses
                """, (rs, row) -> new MarketplaceWindow(rs.getLong("new_users"),
                        rs.getLong("new_businesses")),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to));
    }

    public MarketplaceBacklog marketplaceBacklog() {
        return jdbc.queryForObject("""
                select
                  (select count(*) from users where account_type = 'HUMAN') as total_users,
                  (select count(*) from businesses where status = 'ACTIVE') as active_businesses
                """, (rs, row) -> new MarketplaceBacklog(rs.getLong("total_users"),
                        rs.getLong("active_businesses")));
    }

    public ModerationWindow moderationWindow(Instant from, Instant to) {
        return jdbc.queryForObject("""
                select
                  sum(case when submitted_at >= ? and submitted_at < ? then 1 else 0 end) as submitted,
                  sum(case when decided_at >= ? and decided_at < ? and status = 'APPROVED' then 1 else 0 end) as approved,
                  sum(case when decided_at >= ? and decided_at < ? and status = 'REJECTED' then 1 else 0 end) as rejected,
                  avg(case when decided_at >= ? and decided_at < ? and submitted_at is not null
                    then timestampdiff(microsecond, submitted_at, decided_at) / 1000000.0 end) as avg_review_seconds
                from business_applications
                """, (rs, row) -> new ModerationWindow(rs.getLong("submitted"),
                        rs.getLong("approved"), rs.getLong("rejected"),
                        rs.getBigDecimal("avg_review_seconds")),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to));
    }

    public ModerationBacklog moderationBacklog(Instant now) {
        return jdbc.queryForObject("""
                select
                  sum(case when status in ('PENDING_VERIFICATION','UNDER_REVIEW') then 1 else 0 end) as pending,
                  sum(case when status in ('PENDING_VERIFICATION','UNDER_REVIEW') and reviewer_user_id is null then 1 else 0 end) as unassigned,
                  timestampdiff(second,
                    min(case when status in ('PENDING_VERIFICATION','UNDER_REVIEW') then submitted_at end), ?) as oldest_seconds
                from business_applications
                """, (rs, row) -> new ModerationBacklog(rs.getLong("pending"),
                        rs.getLong("unassigned"), nullableLong(rs, "oldest_seconds")),
                Timestamp.from(now));
    }

    public TrustWindow trustWindow(Instant from, Instant to) {
        return jdbc.queryForObject("""
                select
                  (select count(*) from reports where created_at >= ? and created_at < ?) as reports_submitted,
                  (select count(*) from report_events where event_type = 'REPORT_DISMISSED'
                    and occurred_at >= ? and occurred_at < ?) as reports_dismissed,
                  (select count(*) from report_events where event_type = 'REPORT_MARKED_READY_FOR_INVESTIGATION'
                    and occurred_at >= ? and occurred_at < ?) as reports_ready,
                  (select count(*) from investigation_cases where created_at >= ? and created_at < ?) as cases_opened,
                  (select count(*) from investigation_cases where status = 'CLOSED_NO_ACTION'
                    and closed_at >= ? and closed_at < ?) as cases_closed_no_action,
                  (select count(*) from investigation_cases where status = 'CLOSED_ACTIONED'
                    and closed_at >= ? and closed_at < ?) as cases_closed_actioned,
                  (select count(*) from enforcement_actions where created_at >= ? and created_at < ?) as enforcement_created,
                  (select count(*) from appeals where submitted_at >= ? and submitted_at < ?) as appeals_submitted,
                  (select count(*) from appeals where status = 'UPHELD'
                    and resolved_at >= ? and resolved_at < ?) as appeals_upheld,
                  (select count(*) from appeals where status = 'MODIFIED'
                    and resolved_at >= ? and resolved_at < ?) as appeals_modified,
                  (select count(*) from appeals where status = 'REVOKED'
                    and resolved_at >= ? and resolved_at < ?) as appeals_revoked
                """, (rs, row) -> new TrustWindow(
                        rs.getLong("reports_submitted"), rs.getLong("reports_dismissed"),
                        rs.getLong("reports_ready"), rs.getLong("cases_opened"),
                        rs.getLong("cases_closed_no_action"), rs.getLong("cases_closed_actioned"),
                        rs.getLong("enforcement_created"), rs.getLong("appeals_submitted"),
                        rs.getLong("appeals_upheld"), rs.getLong("appeals_modified"),
                        rs.getLong("appeals_revoked")),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(to));
    }

    public TrustBacklog trustBacklog(Instant now) {
        return jdbc.queryForObject("""
                select
                  (select count(*) from reports where status in
                    ('SUBMITTED','UNDER_TRIAGE','READY_FOR_INVESTIGATION')) as unresolved_reports,
                  (select count(*) from reports where status in
                    ('SUBMITTED','UNDER_TRIAGE','READY_FOR_INVESTIGATION')
                    and assigned_admin_id is null) as unassigned_reports,
                  (select timestampdiff(second, min(created_at), ?) from reports where status in
                    ('SUBMITTED','UNDER_TRIAGE','READY_FOR_INVESTIGATION')) as oldest_report_seconds,
                  (select count(*) from investigation_cases
                    where status in ('OPEN','UNDER_INVESTIGATION','READY_FOR_ACTION')) as open_cases,
                  (select count(*) from investigation_cases where status = 'UNDER_INVESTIGATION') as cases_under_investigation,
                  (select count(*) from investigation_cases where status = 'READY_FOR_ACTION') as cases_ready,
                  (select count(*) from investigation_cases where status in ('OPEN','UNDER_INVESTIGATION','READY_FOR_ACTION')
                    and assigned_admin_id is null) as unassigned_cases,
                  (select timestampdiff(second, min(created_at), ?) from investigation_cases
                    where status in ('OPEN','UNDER_INVESTIGATION','READY_FOR_ACTION')) as oldest_case_seconds,
                  (select count(*) from enforcement_actions where revoked_at is null and effective_at <= ?
                    and (expires_at is null or expires_at > ?)) as active_enforcement,
                  (select count(*) from appeals where status in ('SUBMITTED','UNDER_REVIEW')) as pending_appeals,
                  (select count(*) from appeals where status in ('SUBMITTED','UNDER_REVIEW')
                    and assigned_admin_id is null) as unassigned_appeals,
                  (select timestampdiff(second, min(submitted_at), ?) from appeals
                    where status in ('SUBMITTED','UNDER_REVIEW')) as oldest_appeal_seconds
                """, (rs, row) -> new TrustBacklog(
                        rs.getLong("unresolved_reports"), rs.getLong("unassigned_reports"),
                        nullableLong(rs, "oldest_report_seconds"),
                        rs.getLong("open_cases"), rs.getLong("cases_under_investigation"),
                        rs.getLong("cases_ready"),
                        rs.getLong("unassigned_cases"), nullableLong(rs, "oldest_case_seconds"),
                        rs.getLong("active_enforcement"), rs.getLong("pending_appeals"),
                        rs.getLong("unassigned_appeals"), nullableLong(rs, "oldest_appeal_seconds")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
    }

    public SupportWindow supportWindow(Instant from, Instant to) {
        return jdbc.queryForObject("""
                select
                  (select count(*) from support_tickets where created_at >= ? and created_at < ?) as created_count,
                  (select count(*) from support_tickets where resolved_at >= ? and resolved_at < ?) as resolved_count,
                  (select avg(timestampdiff(microsecond, created_at, resolved_at) / 1000000.0)
                    from support_tickets where resolved_at >= ? and resolved_at < ?) as avg_resolution_seconds,
                  (select avg(timestampdiff(microsecond, t.created_at, response.first_response_at) / 1000000.0)
                    from support_tickets t
                    join (select ticket_id, min(created_at) as first_response_at from support_messages
                      where author_type = 'SUPPORT_ADMIN' group by ticket_id) response on response.ticket_id = t.id
                    where t.created_at >= ? and t.created_at < ?) as avg_first_response_seconds
                """, (rs, row) -> new SupportWindow(rs.getLong("created_count"),
                        rs.getLong("resolved_count"), rs.getBigDecimal("avg_resolution_seconds"),
                        rs.getBigDecimal("avg_first_response_seconds")),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to));
    }

    public SupportBacklog supportBacklog(Instant now) {
        return jdbc.queryForObject("""
                select
                  sum(case when status <> 'RESOLVED' then 1 else 0 end) as open_count,
                  sum(case when status = 'WAITING_FOR_USER' then 1 else 0 end) as waiting_count,
                  sum(case when status <> 'RESOLVED' and assigned_admin_id is null then 1 else 0 end) as unassigned_count,
                  timestampdiff(second, min(case when status <> 'RESOLVED' then created_at end), ?) as oldest_seconds
                from support_tickets
                """, (rs, row) -> new SupportBacklog(rs.getLong("open_count"),
                        rs.getLong("waiting_count"), rs.getLong("unassigned_count"),
                        nullableLong(rs, "oldest_seconds")), Timestamp.from(now));
    }

    public GovernanceBacklog governance(Instant now) {
        return jdbc.queryForObject("""
                select
                  (select count(*) from admin_approval_requests where status = 'PENDING' and expires_at > ?) as pending,
                  (select count(*) from admin_approval_requests where
                    status = 'EXPIRED' or (status in ('PENDING','APPROVED') and expires_at <= ?)) as expired,
                  (select count(*) from admin_approval_requests where status = 'FAILED') as failed,
                  (select count(*) from admin_role_assignments where status = 'ACTIVE'
                    and effective_at <= ? and expires_at is not null and expires_at > ?) as active_elevations
                """, (rs, row) -> new GovernanceBacklog(rs.getLong("pending"),
                        rs.getLong("expired"), rs.getLong("failed"), rs.getLong("active_elevations")),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record LocalWindow(MarketplaceWindow marketplace, ModerationWindow moderation,
            TrustWindow trust, SupportWindow support) { }
    public record LocalBacklog(MarketplaceBacklog marketplace, ModerationBacklog moderation,
            TrustBacklog trust, SupportBacklog support, GovernanceBacklog governance) { }
    public record MarketplaceWindow(long newUsers, long newBusinesses) { }
    public record MarketplaceBacklog(long totalUsers, long activeBusinesses) { }
    public record ModerationWindow(long submitted, long approved, long rejected,
            BigDecimal averageReviewSeconds) { }
    public record ModerationBacklog(long pending, long unassigned, Long oldestSeconds) { }
    public record TrustWindow(long reportsSubmitted, long reportsDismissed, long reportsReady,
            long casesOpened, long casesClosedNoAction, long casesClosedActioned,
            long enforcementCreated, long appealsSubmitted, long appealsUpheld,
            long appealsModified, long appealsRevoked) { }
    public record TrustBacklog(long unresolvedReports, long unassignedReports,
            Long oldestReportSeconds, long openCases, long casesUnderInvestigation, long casesReady,
            long unassignedCases, Long oldestCaseSeconds, long activeEnforcement,
            long pendingAppeals, long unassignedAppeals, Long oldestAppealSeconds) { }
    public record SupportWindow(long created, long resolved, BigDecimal averageResolutionSeconds,
            BigDecimal averageFirstResponseSeconds) { }
    public record SupportBacklog(long open, long waitingForUser, long unassigned,
            Long oldestSeconds) { }
    public record GovernanceBacklog(long pendingApprovals, long expiredApprovals,
            long failedExecutions, long activeTemporaryElevations) { }
    public record NamedCount(String key, String label, long value) { }
    public record ScopeCount(String key, String label, long createdInRange, long currentActive) { }
    public record TrendBucket(Instant bucketStart, long value) { }
}
