package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.reporting.ReportContracts.Assignment;
import com.msb.ecom.auth_service.reporting.ReportContracts.ReasonCode;
import com.msb.ecom.auth_service.reporting.ReportContracts.Severity;
import com.msb.ecom.auth_service.reporting.ReportContracts.Status;
import com.msb.ecom.auth_service.reporting.ReportContracts.TargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
class ReportRepository {
    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    void insert(ReportRow report) {
        jdbc.update("""
                insert into reports (
                    id, reporter_user_id, target_type, target_id, safe_target_label, reason_code,
                    description, severity, status, assigned_admin_id, target_snapshot, evidence_metadata,
                    version, triaged_at, triaged_by, disposition_reason_code, disposition_reason,
                    correlation_id, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, cast(? as json), cast(? as json),
                          0, null, null, null, null, ?, ?, ?)
                """, report.id(), report.reporterUserId(), report.targetType().name(), report.targetId(),
                report.safeTargetLabel(), report.reasonCode().name(), report.description(), report.severity().name(),
                report.status().name(), json(report.targetSnapshot()), json(report.evidenceMetadata()),
                report.correlationId(), Timestamp.from(report.createdAt()), Timestamp.from(report.updatedAt()));
    }

    boolean reserveDuplicate(String reporterId, TargetType type, String targetId, ReasonCode reason,
                             String reportId, Instant now, Instant expiresAt) {
        jdbc.update("""
                delete from report_submission_dedup
                where reporter_user_id = ? and target_type = ? and target_id = ? and reason_code = ?
                  and expires_at <= ?
                """, reporterId, type.name(), targetId, reason.name(), Timestamp.from(now));
        try {
            return jdbc.update("""
                    insert into report_submission_dedup (
                        reporter_user_id, target_type, target_id, reason_code, report_id, expires_at, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """, reporterId, type.name(), targetId, reason.name(), reportId,
                    Timestamp.from(expiresAt), Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    int incrementRate(String reporterId, String bucketType, Instant bucketStart, Instant expiresAt) {
        jdbc.update("""
                insert into report_rate_limit_buckets (
                    reporter_user_id, bucket_type, bucket_start, accepted_count, expires_at
                ) values (?, ?, ?, 1, ?)
                on duplicate key update accepted_count = accepted_count + 1
                """, reporterId, bucketType, Timestamp.from(bucketStart), Timestamp.from(expiresAt));
        Integer count = jdbc.queryForObject("""
                select accepted_count from report_rate_limit_buckets
                where reporter_user_id = ? and bucket_type = ? and bucket_start = ?
                """, Integer.class, reporterId, bucketType, Timestamp.from(bucketStart));
        return count == null ? 0 : count;
    }

    void insertEvent(EventRow event) {
        jdbc.update("""
                insert into report_events (
                    event_id, report_id, event_type, occurred_at, actor_type, actor_id,
                    actor_display_name, source, previous_state, new_state, reason_code, reason,
                    correlation_id, request_id, safe_metadata
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json))
                """, event.eventId(), event.reportId(), event.eventType(), Timestamp.from(event.occurredAt()),
                event.actorType(), event.actorId(), event.actorDisplayName(), event.source(),
                event.previousState(), event.newState(), event.reasonCode(), event.reason(),
                event.correlationId(), event.requestId(), json(event.safeMetadata()));
    }

    Optional<ReportRow> find(String reportId) {
        return jdbc.query(BASE_SELECT + " where r.id = ?", this::report, reportId).stream().findFirst();
    }

    SearchResult search(Search search) {
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> params = new ArrayList<>();
        if (search.query() != null) {
            where.append(" and (r.id = ? or r.target_id = ? or r.reporter_user_id = ?)\n");
            params.add(search.query()); params.add(search.query()); params.add(search.query());
        }
        addEnum(where, params, "r.status", search.status());
        addEnum(where, params, "r.target_type", search.targetType());
        addEnum(where, params, "r.reason_code", search.reasonCode());
        addEnum(where, params, "r.severity", search.severity());
        if (search.unresolvedOnly()) {
            where.append(" and r.status in ('SUBMITTED','UNDER_TRIAGE','READY_FOR_INVESTIGATION')\n");
        }
        if (search.assignment() == Assignment.UNASSIGNED) where.append(" and r.assigned_admin_id is null\n");
        if (search.assignment() == Assignment.ASSIGNED) where.append(" and r.assigned_admin_id is not null\n");
        if (search.assignment() == Assignment.ASSIGNED_TO_ME) { where.append(" and r.assigned_admin_id = ?\n"); params.add(search.adminId()); }
        if (search.createdFrom() != null) { where.append(" and r.created_at >= ?\n"); params.add(Timestamp.from(search.createdFrom())); }
        if (search.createdTo() != null) { where.append(" and r.created_at <= ?\n"); params.add(Timestamp.from(search.createdTo())); }

        long total = Optional.ofNullable(jdbc.queryForObject("select count(*) from reports r" + where,
                Long.class, params.toArray())).orElse(0L);
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(search.size()); pageParams.add(search.page() * search.size());
        List<SearchRow> items = jdbc.query(SEARCH_SELECT + where + " order by " + sortSql(search.sort())
                + " limit ? offset ?", (rs, rowNum) -> new SearchRow(report(rs, rowNum),
                rs.getLong("related_report_count")), pageParams.toArray());
        return new SearchResult(items, total);
    }

    List<ReportRow> related(TargetType type, String targetId, String excludedId, int limit) {
        return jdbc.query(BASE_SELECT + """
                where r.target_type = ? and r.target_id = ? and r.id <> ?
                order by r.created_at desc, r.id desc limit ?
                """, this::report, type.name(), targetId, excludedId, limit);
    }

    List<EventRow> events(String reportId) {
        return jdbc.query("""
                select event_id, report_id, event_type, occurred_at, actor_type, actor_id,
                       actor_display_name, source, previous_state, new_state, reason_code, reason,
                       correlation_id, request_id, safe_metadata
                from report_events where report_id = ? order by occurred_at, event_id
                """, this::event, reportId);
    }

    boolean claim(String reportId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update reports set status = 'UNDER_TRIAGE', assigned_admin_id = ?, version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'SUBMITTED' and assigned_admin_id is null
                """, adminId, Timestamp.from(now), reportId, version) == 1;
    }

    boolean release(String reportId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update reports set status = 'SUBMITTED', assigned_admin_id = null, version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'UNDER_TRIAGE' and assigned_admin_id = ?
                """, Timestamp.from(now), reportId, version, adminId) == 1;
    }

    boolean severity(String reportId, long version, String adminId, Severity severity, Instant now) {
        return jdbc.update("""
                update reports set severity = ?, version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'UNDER_TRIAGE' and assigned_admin_id = ?
                """, severity.name(), Timestamp.from(now), reportId, version, adminId) == 1;
    }

    boolean resolve(String reportId, long version, String adminId, Status status,
                    String reasonCode, String reason, Instant now) {
        return jdbc.update("""
                update reports set status = ?, version = version + 1, triaged_at = ?, triaged_by = ?,
                    disposition_reason_code = ?, disposition_reason = ?, updated_at = ?
                where id = ? and version = ? and status = 'UNDER_TRIAGE' and assigned_admin_id = ?
                """, status.name(), Timestamp.from(now), adminId, reasonCode, reason, Timestamp.from(now),
                reportId, version, adminId) == 1;
    }

    Optional<SafeUser> safeUser(String userId) {
        return jdbc.query("""
                select id, coalesce(nullif(trim(display_name), ''), 'Marketplace user') display_name,
                       status, account_type, version from users where id = ?
                """, (rs, n) -> new SafeUser(rs.getString("id"), rs.getString("display_name"),
                rs.getString("status"), rs.getString("account_type"), rs.getLong("version")), userId).stream().findFirst();
    }

    Optional<SafeBusiness> safeBusiness(String businessId) {
        return jdbc.query("""
                select b.id, coalesce(nullif(trim(s.name), ''), b.legal_name) display_name,
                       b.status, b.version, s.id store_id, s.status store_status,
                       (select bm.user_id from business_memberships bm
                        where bm.business_id = b.id and bm.role = 'OWNER' and bm.status = 'ACTIVE'
                        order by bm.created_at limit 1) owner_user_id
                from businesses b left join stores s on s.business_id = b.id where b.id = ?
                """, (rs, n) -> new SafeBusiness(rs.getString("id"), rs.getString("display_name"),
                rs.getString("status"), rs.getLong("version"), rs.getString("store_id"),
                rs.getString("store_status"), rs.getString("owner_user_id")), businessId).stream().findFirst();
    }

    boolean isActiveBusinessMember(String businessId, String userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from business_memberships where business_id = ? and user_id = ? and status = 'ACTIVE'
                """, Integer.class, businessId, userId);
        return count != null && count > 0;
    }

    String safeUserLabel(String userId) {
        return safeUser(userId).map(SafeUser::displayName).orElse("Marketplace user");
    }

    private static void addEnum(StringBuilder where, List<Object> params, String column, Enum<?> value) {
        if (value != null) { where.append(" and ").append(column).append(" = ?\n"); params.add(value.name()); }
    }

    private String sortSql(String sort) {
        return switch (sort) {
            case "createdAt,asc" -> "r.created_at asc, r.id asc";
            case "severity,desc" -> "field(r.severity, 'CRITICAL','HIGH','MEDIUM','LOW'), r.created_at asc, r.id asc";
            case "updatedAt,desc" -> "r.updated_at desc, r.id desc";
            default -> "r.created_at desc, r.id desc";
        };
    }

    private ReportRow report(ResultSet rs, int row) throws SQLException {
        return new ReportRow(rs.getString("id"), rs.getString("reporter_user_id"), TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"), rs.getString("safe_target_label"), ReasonCode.valueOf(rs.getString("reason_code")),
                rs.getString("description"), Severity.valueOf(rs.getString("severity")), Status.valueOf(rs.getString("status")),
                rs.getString("assigned_admin_id"), tree(rs.getString("target_snapshot")), metadata(rs.getString("evidence_metadata")),
                rs.getLong("version"), instant(rs, "triaged_at"), rs.getString("triaged_by"),
                rs.getString("disposition_reason_code"), rs.getString("disposition_reason"), rs.getString("correlation_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private EventRow event(ResultSet rs, int row) throws SQLException {
        return new EventRow(rs.getString("event_id"), rs.getString("report_id"), rs.getString("event_type"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_type"), rs.getString("actor_id"),
                rs.getString("actor_display_name"), rs.getString("source"), rs.getString("previous_state"),
                rs.getString("new_state"), rs.getString("reason_code"), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("request_id"), metadata(rs.getString("safe_metadata")));
    }

    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private JsonNode tree(String value) { try { return mapper.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private Map<String, String> metadata(String value) { try { return value == null ? Map.of() : mapper.readValue(value, METADATA); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }

    private static final String BASE_SELECT = """
            select r.id, r.reporter_user_id, r.target_type, r.target_id, r.safe_target_label,
                   r.reason_code, r.description, r.severity, r.status, r.assigned_admin_id,
                   r.target_snapshot, r.evidence_metadata, r.version, r.triaged_at, r.triaged_by,
                   r.disposition_reason_code, r.disposition_reason, r.correlation_id, r.created_at, r.updated_at
            from reports r
            """;
    private static final String SEARCH_SELECT = """
            select r.id, r.reporter_user_id, r.target_type, r.target_id, r.safe_target_label,
                   r.reason_code, r.description, r.severity, r.status, r.assigned_admin_id,
                   r.target_snapshot, r.evidence_metadata, r.version, r.triaged_at, r.triaged_by,
                   r.disposition_reason_code, r.disposition_reason, r.correlation_id, r.created_at, r.updated_at,
                   (select count(*) from reports related
                    where related.target_type = r.target_type and related.target_id = r.target_id
                      and related.id <> r.id) related_report_count
            from reports r
            """;

    record Search(String query, Status status, TargetType targetType, ReasonCode reasonCode, Severity severity,
                  Assignment assignment, boolean unresolvedOnly, Instant createdFrom, Instant createdTo,
                  int page, int size, String sort, String adminId) { }
    record SearchResult(List<SearchRow> items, long total) { }
    record SearchRow(ReportRow report, long relatedReportCount) { }
    record SafeUser(String id, String displayName, String status, String accountType, long version) { }
    record SafeBusiness(String id, String displayName, String status, long version, String storeId, String storeStatus, String ownerUserId) { }
    record ReportRow(String id, String reporterUserId, TargetType targetType, String targetId, String safeTargetLabel,
                     ReasonCode reasonCode, String description, Severity severity, Status status, String assignedAdminId,
                     JsonNode targetSnapshot, Map<String, String> evidenceMetadata, long version, Instant triagedAt,
                     String triagedBy, String dispositionReasonCode, String dispositionReason, String correlationId,
                     Instant createdAt, Instant updatedAt) { }
    record EventRow(String eventId, String reportId, String eventType, Instant occurredAt, String actorType,
                    String actorId, String actorDisplayName, String source, String previousState, String newState,
                    String reasonCode, String reason, String correlationId, String requestId,
                    Map<String, String> safeMetadata) { }
}
