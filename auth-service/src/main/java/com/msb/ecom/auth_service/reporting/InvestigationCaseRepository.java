package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.*;

@Repository
@RequiredArgsConstructor
class InvestigationCaseRepository {
    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    void insertCase(CaseRow value) {
        jdbc.update("""
                insert into investigation_cases (
                    id, title, status, severity, primary_target_type, primary_target_id,
                    safe_primary_target_label, assigned_admin_id, created_by_admin_id,
                    conclusion_code, conclusion_reason, ready_for_action_at, closed_at,
                    correlation_id, version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, null, ?, null, null, null, null, ?, 0, ?, ?)
                """, value.id(), value.title(), value.status().name(), value.severity().name(),
                value.primaryTargetType().name(), value.primaryTargetId(), value.safePrimaryTargetLabel(),
                value.createdByAdminId(), value.correlationId(), Timestamp.from(value.createdAt()),
                Timestamp.from(value.updatedAt()));
    }

    void insertReportLink(String caseId, String reportId, String adminId, Instant now) {
        jdbc.update("""
                insert into investigation_case_reports (case_id, report_id, linked_at, linked_by_admin_id)
                values (?, ?, ?, ?)
                """, caseId, reportId, Timestamp.from(now), adminId);
    }

    void insertTarget(String caseId, ReportContracts.TargetType type, String targetId, String label,
                      RelationshipType relationship, String adminId, Instant now) {
        jdbc.update("""
                insert into investigation_case_targets (
                    case_id, target_type, target_id, safe_target_label, relationship_type,
                    linked_at, linked_by_admin_id
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, caseId, type.name(), targetId, label, relationship.name(), Timestamp.from(now), adminId);
    }

    boolean markReportLinked(String reportId, long expectedVersion, Instant now) {
        return jdbc.update("""
                update reports set status = 'LINKED_TO_CASE', version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'READY_FOR_INVESTIGATION'
                """, Timestamp.from(now), reportId, expectedVersion) == 1;
    }

    boolean restoreReportReady(String reportId, long expectedVersion, Instant now) {
        return jdbc.update("""
                update reports set status = 'READY_FOR_INVESTIGATION', version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'LINKED_TO_CASE'
                """, Timestamp.from(now), reportId, expectedVersion) == 1;
    }

    Optional<String> caseIdForReport(String reportId) {
        return jdbc.queryForList("select case_id from investigation_case_reports where report_id = ?",
                String.class, reportId).stream().findFirst();
    }

    boolean deleteReportLink(String caseId, String reportId) {
        return jdbc.update("delete from investigation_case_reports where case_id = ? and report_id = ?",
                caseId, reportId) == 1;
    }

    boolean deleteRelatedTarget(String caseId, ReportContracts.TargetType type, String targetId) {
        return jdbc.update("""
                delete from investigation_case_targets
                where case_id = ? and target_type = ? and target_id = ? and relationship_type = 'RELATED'
                """, caseId, type.name(), targetId) == 1;
    }

    boolean hasTarget(String caseId, ReportContracts.TargetType type, String targetId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from investigation_case_targets
                where case_id = ? and target_type = ? and target_id = ?
                """, Integer.class, caseId, type.name(), targetId);
        return count != null && count > 0;
    }

    Optional<CaseRow> find(String caseId) {
        return jdbc.query(CASE_SELECT + " where c.id = ?", this::caseRow, caseId).stream().findFirst();
    }

    SearchResult search(Search search) {
        StringBuilder where = new StringBuilder(" where 1 = 1\n");
        List<Object> params = new ArrayList<>();
        if (search.query() != null) {
            where.append(" and (c.id = ? or c.primary_target_id = ? or lower(c.title) like ? escape '!')\n");
            params.add(search.query());
            params.add(search.query());
            params.add("%" + escapedLike(search.query().toLowerCase()) + "%");
        }
        addEnum(where, params, "c.status", search.status());
        addEnum(where, params, "c.severity", search.severity());
        addEnum(where, params, "c.primary_target_type", search.targetType());
        if (search.assignment() == Assignment.UNASSIGNED) where.append(" and c.assigned_admin_id is null\n");
        if (search.assignment() == Assignment.ASSIGNED) where.append(" and c.assigned_admin_id is not null\n");
        if (search.assignment() == Assignment.ASSIGNED_TO_ME) {
            where.append(" and c.assigned_admin_id = ?\n");
            params.add(search.adminId());
        }
        if (search.createdFrom() != null) {
            where.append(" and c.created_at >= ?\n");
            params.add(Timestamp.from(search.createdFrom()));
        }
        if (search.createdTo() != null) {
            where.append(" and c.created_at <= ?\n");
            params.add(Timestamp.from(search.createdTo()));
        }
        if (search.updatedFrom() != null) {
            where.append(" and c.updated_at >= ?\n");
            params.add(Timestamp.from(search.updatedFrom()));
        }
        if (search.updatedTo() != null) {
            where.append(" and c.updated_at <= ?\n");
            params.add(Timestamp.from(search.updatedTo()));
        }
        Long totalValue = jdbc.queryForObject("select count(*) from investigation_cases c" + where,
                Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(search.size());
        pageParams.add(search.page() * search.size());
        List<CaseRow> rows = jdbc.query(CASE_SELECT + where + " order by " + sortSql(search.sort())
                + " limit ? offset ?", this::caseRow, pageParams.toArray());
        return new SearchResult(rows, totalValue == null ? 0 : totalValue);
    }

    List<ReportLinkRow> reports(String caseId) {
        return jdbc.query("""
                select r.id, r.reason_code, r.severity, r.status, r.safe_target_label, r.description,
                       r.target_snapshot, r.created_at, r.version
                from investigation_case_reports cr
                join reports r on r.id = cr.report_id
                where cr.case_id = ?
                order by cr.linked_at asc, r.id asc
                """, (rs, row) -> new ReportLinkRow(rs.getString("id"),
                ReportContracts.ReasonCode.valueOf(rs.getString("reason_code")),
                ReportContracts.Severity.valueOf(rs.getString("severity")),
                ReportContracts.Status.valueOf(rs.getString("status")), rs.getString("safe_target_label"),
                rs.getString("description"), tree(rs.getString("target_snapshot")),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("version")), caseId);
    }

    List<TargetRow> targets(String caseId) {
        return jdbc.query("""
                select target_type, target_id, safe_target_label, relationship_type, linked_at
                from investigation_case_targets where case_id = ?
                order by case when relationship_type = 'PRIMARY' then 0 else 1 end, linked_at, target_id
                """, (rs, row) -> new TargetRow(ReportContracts.TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"), rs.getString("safe_target_label"),
                RelationshipType.valueOf(rs.getString("relationship_type")),
                rs.getTimestamp("linked_at").toInstant()), caseId);
    }

    List<NoteRow> notes(String caseId) {
        return jdbc.query("""
                select id, body, author_admin_id, author_display_name, created_at, correlation_id
                from investigation_case_notes where case_id = ? order by created_at, id
                """, (rs, row) -> new NoteRow(rs.getString("id"), rs.getString("body"),
                rs.getString("author_admin_id"), rs.getString("author_display_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("correlation_id")), caseId);
    }

    Optional<NoteRow> noteByRetry(String caseId, String adminId, String idempotencyKey) {
        return jdbc.query("""
                select id, body, author_admin_id, author_display_name, created_at, correlation_id
                from investigation_case_notes
                where case_id = ? and author_admin_id = ? and idempotency_key = ?
                """, (rs, row) -> new NoteRow(rs.getString("id"), rs.getString("body"),
                rs.getString("author_admin_id"), rs.getString("author_display_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("correlation_id")),
                caseId, adminId, idempotencyKey).stream().findFirst();
    }

    boolean insertNote(NoteInsert value) {
        try {
            return jdbc.update("""
                    insert into investigation_case_notes (
                        id, case_id, body, author_admin_id, author_display_name,
                        correlation_id, idempotency_key, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, value.id(), value.caseId(), value.body(), value.adminId(), value.adminName(),
                    value.correlationId(), value.idempotencyKey(), Timestamp.from(value.createdAt())) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    List<EvidenceRow> evidence(String caseId) {
        return jdbc.query("""
                select id, evidence_type, reference_type, reference_id, label, snapshot_metadata,
                       added_by_admin_id, created_at
                from investigation_case_evidence where case_id = ? order by created_at, id
                """, (rs, row) -> new EvidenceRow(rs.getString("id"),
                EvidenceType.valueOf(rs.getString("evidence_type")),
                ReferenceType.valueOf(rs.getString("reference_type")), rs.getString("reference_id"),
                rs.getString("label"), tree(rs.getString("snapshot_metadata")),
                rs.getString("added_by_admin_id"), rs.getTimestamp("created_at").toInstant()), caseId);
    }

    boolean insertEvidence(EvidenceInsert value) {
        try {
            return jdbc.update("""
                    insert into investigation_case_evidence (
                        id, case_id, evidence_type, reference_type, reference_id, label,
                        snapshot_metadata, added_by_admin_id, correlation_id, created_at
                    ) values (?, ?, ?, ?, ?, ?, cast(? as json), ?, ?, ?)
                    """, value.id(), value.caseId(), value.evidenceType().name(), value.referenceType().name(),
                    value.referenceId(), value.label(), json(value.snapshotMetadata()), value.adminId(),
                    value.correlationId(), Timestamp.from(value.createdAt())) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    void insertEvent(EventRow event) {
        jdbc.update("""
                insert into investigation_case_events (
                    event_id, case_id, event_type, occurred_at, actor_type, actor_id,
                    actor_display_name, source, previous_state, new_state, reason_code,
                    reason, correlation_id, request_id, safe_metadata
                ) values (?, ?, ?, ?, 'PLATFORM_ADMIN', ?, ?, 'HUMAN_ADMIN', ?, ?, ?, ?, ?, ?, cast(? as json))
                """, event.eventId(), event.caseId(), event.eventType(), Timestamp.from(event.occurredAt()),
                event.actorId(), event.actorDisplayName(), event.previousState(), event.newState(),
                event.reasonCode(), event.reason(), event.correlationId(), event.requestId(),
                json(event.safeMetadata()));
    }

    List<EventRow> events(String caseId) {
        return jdbc.query("""
                select event_id, case_id, event_type, occurred_at, actor_id, actor_display_name,
                       previous_state, new_state, reason_code, reason, correlation_id, request_id, safe_metadata
                from investigation_case_events where case_id = ? order by occurred_at, event_id
                """, (rs, row) -> new EventRow(rs.getString("event_id"), rs.getString("case_id"),
                rs.getString("event_type"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_id"), rs.getString("actor_display_name"), rs.getString("previous_state"),
                rs.getString("new_state"), rs.getString("reason_code"), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("request_id"),
                metadata(rs.getString("safe_metadata"))), caseId);
    }

    boolean claim(String caseId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update investigation_cases set assigned_admin_id = ?, version = version + 1, updated_at = ?
                where id = ? and version = ? and status in ('OPEN', 'UNDER_INVESTIGATION')
                  and assigned_admin_id is null
                """, adminId, Timestamp.from(now), caseId, version) == 1;
    }

    boolean release(String caseId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update investigation_cases set assigned_admin_id = null, version = version + 1, updated_at = ?
                where id = ? and version = ? and status in ('OPEN', 'UNDER_INVESTIGATION')
                  and assigned_admin_id = ?
                """, Timestamp.from(now), caseId, version, adminId) == 1;
    }

    boolean start(String caseId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update investigation_cases set status = 'UNDER_INVESTIGATION', version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'OPEN' and assigned_admin_id = ?
                """, Timestamp.from(now), caseId, version, adminId) == 1;
    }

    boolean touchInvestigableOwned(String caseId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update investigation_cases set version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'UNDER_INVESTIGATION' and assigned_admin_id = ?
                """, Timestamp.from(now), caseId, version, adminId) == 1;
    }

    boolean severity(String caseId, long version, String adminId, ReportContracts.Severity severity, Instant now) {
        return jdbc.update("""
                update investigation_cases set severity = ?, version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'UNDER_INVESTIGATION' and assigned_admin_id = ?
                """, severity.name(), Timestamp.from(now), caseId, version, adminId) == 1;
    }

    boolean conclude(String caseId, long version, String adminId, Status status, String conclusionCode,
                     String reason, Instant now) {
        return jdbc.update("""
                update investigation_cases set status = ?, conclusion_code = ?, conclusion_reason = ?,
                    ready_for_action_at = ?, closed_at = ?, version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'UNDER_INVESTIGATION' and assigned_admin_id = ?
                """, status.name(), conclusionCode, reason,
                status == Status.READY_FOR_ACTION ? Timestamp.from(now) : null,
                status == Status.CLOSED_NO_ACTION ? Timestamp.from(now) : null,
                Timestamp.from(now), caseId, version, adminId) == 1;
    }

    boolean touchReadyOwned(String caseId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update investigation_cases set version=version+1, updated_at=?
                where id=? and version=? and status='READY_FOR_ACTION' and assigned_admin_id=?
                """, Timestamp.from(now), caseId, version, adminId) == 1;
    }

    boolean touchReady(String caseId, long version, Instant now) {
        return jdbc.update("""
                update investigation_cases set version=version+1, updated_at=?
                where id=? and version=? and status='READY_FOR_ACTION'
                """, Timestamp.from(now), caseId, version) == 1;
    }

    void touchReadyAfterExecution(String caseId, Instant now) {
        jdbc.update("""
                update investigation_cases set version=version+1, updated_at=?
                where id=? and status='READY_FOR_ACTION'
                """, Timestamp.from(now), caseId);
    }

    boolean closeActioned(String caseId, long version, String adminId, String reason, Instant now) {
        return jdbc.update("""
                update investigation_cases set status='CLOSED_ACTIONED', conclusion_code='ACTION_COMPLETED',
                    conclusion_reason=?, closed_at=?, version=version+1, updated_at=?
                where id=? and version=? and status='READY_FOR_ACTION' and assigned_admin_id=?
                """, reason, Timestamp.from(now), Timestamp.from(now), caseId, version, adminId) == 1;
    }

    private CaseRow caseRow(ResultSet rs, int row) throws SQLException {
        return new CaseRow(rs.getString("id"), rs.getString("title"),
                Status.valueOf(rs.getString("status")),
                ReportContracts.Severity.valueOf(rs.getString("severity")),
                ReportContracts.TargetType.valueOf(rs.getString("primary_target_type")),
                rs.getString("primary_target_id"), rs.getString("safe_primary_target_label"),
                rs.getString("assigned_admin_id"), rs.getString("created_by_admin_id"),
                rs.getString("conclusion_code"), rs.getString("conclusion_reason"),
                instant(rs, "ready_for_action_at"), instant(rs, "closed_at"), rs.getString("correlation_id"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("report_count"),
                rs.getLong("target_count"), rs.getLong("note_count"));
    }

    private static void addEnum(StringBuilder sql, List<Object> params, String column, Enum<?> value) {
        if (value != null) {
            sql.append(" and ").append(column).append(" = ?\n");
            params.add(value.name());
        }
    }

    private String sortSql(String sort) {
        return switch (sort) {
            case "createdAt,asc" -> "c.created_at asc, c.id asc";
            case "severity,desc" -> "field(c.severity, 'CRITICAL','HIGH','MEDIUM','LOW'), c.updated_at desc, c.id desc";
            case "updatedAt,asc" -> "c.updated_at asc, c.id asc";
            default -> "c.updated_at desc, c.id desc";
        };
    }

    private static String escapedLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private JsonNode tree(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private Map<String, String> metadata(String value) {
        try { return value == null ? Map.of() : mapper.readValue(value, METADATA); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final String CASE_SELECT = """
            select c.id, c.title, c.status, c.severity, c.primary_target_type, c.primary_target_id,
                   c.safe_primary_target_label, c.assigned_admin_id, c.created_by_admin_id,
                   c.conclusion_code, c.conclusion_reason, c.ready_for_action_at, c.closed_at,
                   c.correlation_id, c.version, c.created_at, c.updated_at,
                   (select count(*) from investigation_case_reports cr where cr.case_id = c.id) report_count,
                   (select count(*) from investigation_case_targets ct where ct.case_id = c.id) target_count,
                   (select count(*) from investigation_case_notes cn where cn.case_id = c.id) note_count
            from investigation_cases c
            """;

    record Search(String query, Status status, ReportContracts.Severity severity,
                  ReportContracts.TargetType targetType, Assignment assignment, Instant createdFrom,
                  Instant createdTo, Instant updatedFrom, Instant updatedTo, int page, int size,
                  String sort, String adminId) { }
    record SearchResult(List<CaseRow> rows, long total) { }
    record CaseRow(String id, String title, Status status, ReportContracts.Severity severity,
                   ReportContracts.TargetType primaryTargetType, String primaryTargetId,
                   String safePrimaryTargetLabel, String assignedAdminId, String createdByAdminId,
                   String conclusionCode, String conclusionReason, Instant readyForActionAt, Instant closedAt,
                   String correlationId, long version, Instant createdAt, Instant updatedAt,
                   long reportCount, long targetCount, long noteCount) { }
    record ReportLinkRow(String reportId, ReportContracts.ReasonCode reasonCode,
                         ReportContracts.Severity severity, ReportContracts.Status status,
                         String safeTargetLabel, String description, JsonNode targetSnapshot,
                         Instant createdAt, long version) { }
    record TargetRow(ReportContracts.TargetType targetType, String targetId, String safeTargetLabel,
                     RelationshipType relationshipType, Instant linkedAt) { }
    record NoteRow(String id, String body, String adminId, String adminName, Instant createdAt,
                   String correlationId) { }
    record EvidenceRow(String id, EvidenceType evidenceType, ReferenceType referenceType, String referenceId,
                       String label, JsonNode snapshotMetadata, String adminId, Instant createdAt) { }
    record NoteInsert(String id, String caseId, String body, String adminId, String adminName,
                      String correlationId, String idempotencyKey, Instant createdAt) { }
    record EvidenceInsert(String id, String caseId, EvidenceType evidenceType, ReferenceType referenceType,
                          String referenceId, String label, JsonNode snapshotMetadata, String adminId,
                          String correlationId, Instant createdAt) { }
    record EventRow(String eventId, String caseId, String eventType, Instant occurredAt, String actorId,
                    String actorDisplayName, String previousState, String newState, String reasonCode,
                    String reason, String correlationId, String requestId, Map<String, String> safeMetadata) { }
}
