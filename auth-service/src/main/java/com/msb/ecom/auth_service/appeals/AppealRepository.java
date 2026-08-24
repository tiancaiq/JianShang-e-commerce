package com.msb.ecom.auth_service.appeals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.appeals.AppealContracts.*;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
@RequiredArgsConstructor
class AppealRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    Set<String> authorizedBusinessIds(String userId) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                select bm.business_id
                from business_memberships bm
                join businesses b on b.id = bm.business_id
                where bm.user_id = ?
                  and bm.status = 'ACTIVE'
                  and bm.role in ('OWNER','MANAGER')
                  and b.status = 'ACTIVE'
                order by bm.business_id
                """, String.class, userId));
    }

    Optional<EnforcementRow> localEnforcement(String actionId) {
        return jdbc.query(LOCAL_ENFORCEMENT_SELECT + " where ea.id = ?", this::enforcement, actionId)
                .stream().findFirst().map(this::withScopes);
    }

    List<EnforcementRow> localActiveForActor(String userId, Set<String> businessIds, Instant now) {
        List<Object> params = new ArrayList<>();
        params.add(userId);
        String business = "";
        if (businessIds != null && !businessIds.isEmpty()) {
            business = " or (ea.target_type = 'BUSINESS' and ea.target_id in ("
                    + String.join(",", businessIds.stream().map(ignored -> "?").toList()) + "))";
            businessIds.stream().sorted().forEach(params::add);
        }
        params.add(Timestamp.from(now));
        params.add(Timestamp.from(now));
        String sql = LOCAL_ENFORCEMENT_SELECT + """
                 where ((ea.target_type = 'USER' and ea.target_id = ?)%s)
                   and ea.revoked_at is null and ea.effective_at <= ?
                   and (ea.expires_at is null or ea.expires_at > ?)
                 order by ea.created_at desc, ea.id desc
                """.formatted(business);
        return jdbc.query(sql, this::enforcement, params.toArray()).stream().map(this::withScopes).toList();
    }

    Optional<AppealRow> byAction(String actionId) {
        return jdbc.query(APPEAL_SELECT + " where a.enforcement_action_id = ?", this::appeal, actionId)
                .stream().findFirst();
    }

    Optional<AppealRow> byId(String appealId, boolean lock) {
        return jdbc.query(APPEAL_SELECT + " where a.id = ?" + (lock ? " for update" : ""),
                this::appeal, appealId).stream().findFirst();
    }

    Optional<AppealRow> byResolutionKey(String idempotencyKey, boolean lock) {
        return jdbc.query(APPEAL_SELECT + " where a.resolution_idempotency_key = ?"
                        + (lock ? " for update" : ""), this::appeal, idempotencyKey)
                .stream().findFirst();
    }

    boolean insert(AppealRow row) {
        try {
            return jdbc.update("""
                    insert into appeals (
                        id, enforcement_action_id, target_type, target_id, safe_target_label,
                        appellant_type, appellant_user_id, status, reason_code, explanation,
                        safe_evidence_references, submitted_at, original_case_id,
                        original_enforcement_version, correlation_id, version, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, cast(? as json), ?, ?, ?, ?, 0, ?, ?)
                    """, row.id(), row.enforcementActionId(), row.targetType().name(), row.targetId(),
                    row.safeTargetLabel(), row.appellantType().name(), row.appellantUserId(),
                    row.reasonCode().name(), row.explanation(), json(row.evidenceReferences()),
                    Timestamp.from(row.submittedAt()), row.originalCaseId(), row.originalEnforcementVersion(),
                    row.correlationId(), Timestamp.from(row.createdAt()), Timestamp.from(row.updatedAt())) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    List<AppealRow> mine(String userId) {
        return jdbc.query(APPEAL_SELECT + " where a.appellant_user_id = ? order by a.submitted_at desc, a.id desc",
                this::appeal, userId);
    }

    SearchResult search(Search search) {
        StringBuilder where = new StringBuilder(" where 1=1\n");
        List<Object> params = new ArrayList<>();
        if (search.query() != null) {
            where.append(" and (a.id = ? or a.enforcement_action_id = ? or a.target_id = ?)\n");
            params.add(search.query()); params.add(search.query()); params.add(search.query());
        }
        addEnum(where, params, "a.target_type", search.targetType());
        addEnum(where, params, "a.status", search.status());
        addEnum(where, params, "a.reason_code", search.reasonCode());
        if (search.assignment() != null && search.assignment() != Assignment.ALL) {
            switch (search.assignment()) {
                case UNASSIGNED -> where.append(" and a.assigned_admin_id is null\n");
                case ASSIGNED_TO_ME -> { where.append(" and a.assigned_admin_id = ?\n"); params.add(search.adminId()); }
                case ASSIGNED -> where.append(" and a.assigned_admin_id is not null\n");
                default -> { }
            }
        }
        if (search.submittedFrom() != null) { where.append(" and a.submitted_at >= ?\n"); params.add(Timestamp.from(search.submittedFrom())); }
        if (search.submittedTo() != null) { where.append(" and a.submitted_at < ?\n"); params.add(Timestamp.from(search.submittedTo())); }
        Long total = jdbc.queryForObject("select count(*) from appeals a" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(search.size()); pageParams.add((long) search.page() * search.size());
        List<AppealRow> rows = jdbc.query(APPEAL_SELECT + where + " order by " + sortSql(search.sort())
                + " limit ? offset ?", this::appeal, pageParams.toArray());
        return new SearchResult(rows, total == null ? 0 : total);
    }

    boolean claim(String appealId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update appeals set assigned_admin_id=?, version=version+1, updated_at=?
                where id=? and version=? and assigned_admin_id is null
                  and status in ('SUBMITTED','UNDER_REVIEW')
                """, adminId, Timestamp.from(now), appealId, version) == 1;
    }

    boolean release(String appealId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update appeals set assigned_admin_id=null, version=version+1, updated_at=?
                where id=? and version=? and assigned_admin_id=?
                  and status in ('SUBMITTED','UNDER_REVIEW')
                """, Timestamp.from(now), appealId, version, adminId) == 1;
    }

    boolean start(String appealId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update appeals set status='UNDER_REVIEW', review_started_at=?, version=version+1, updated_at=?
                where id=? and version=? and assigned_admin_id=? and status='SUBMITTED'
                """, Timestamp.from(now), Timestamp.from(now), appealId, version, adminId) == 1;
    }

    boolean touchForNote(String appealId, long version, String adminId, Instant now) {
        return jdbc.update("""
                update appeals set version=version+1, updated_at=?
                where id=? and version=? and assigned_admin_id=? and status='UNDER_REVIEW'
                """, Timestamp.from(now), appealId, version, adminId) == 1;
    }

    boolean recommend(String appealId, long version, String adminId, ReviewOutcome outcome,
                      String reasonCode, String reason, ReplacementProposal replacement, Instant now) {
        int updated = jdbc.update("""
                update appeals set status=?, reviewed_at=?, review_outcome=?, review_reason_code=?,
                    review_reason=?, replacement_action_type=?, replacement_expires_at=?,
                    replacement_reason_code=?, replacement_reason=?, replacement_expected_target_version=?,
                    version=version+1, updated_at=?
                where id=? and version=? and assigned_admin_id=? and status='UNDER_REVIEW'
                """, outcome.name(), Timestamp.from(now), outcome.name(), reasonCode, reason,
                replacement == null ? null : replacement.actionType().name(),
                replacement == null || replacement.expiresAt() == null ? null : Timestamp.from(replacement.expiresAt()),
                replacement == null ? null : replacement.reasonCode(),
                replacement == null ? null : replacement.reason(),
                replacement == null ? null : replacement.expectedTargetVersion(),
                Timestamp.from(now), appealId, version, adminId);
        if (updated == 1 && replacement != null) {
            replacement.scopes().stream().sorted(Comparator.comparing(Enum::name)).forEach(scope -> jdbc.update(
                    "insert into appeal_replacement_scopes (appeal_id, scope) values (?, ?)", appealId, scope.name()));
        }
        return updated == 1;
    }

    boolean finalizeResolution(String appealId, long version, Status expectedStatus, FinalOutcome outcome,
                               String adminId, String adminName, String summary,
                               String idempotencyKey, String requestHash,
                               String replacementEnforcementActionId,
                               long originalEnforcementVersion, long targetVersion, Instant now) {
        try {
            return jdbc.update("""
                    update appeals
                    set status=?, resolved_at=?, resolved_by_admin_id=?, resolved_by_admin_display_name=?,
                        resolution_summary=?, resolution_idempotency_key=?, resolution_request_hash=?,
                        replacement_enforcement_action_id=?, resolution_original_enforcement_version=?,
                        resolution_target_version=?, version=version+1, updated_at=?
                    where id=? and version=? and status=?
                    """, outcome.name(), Timestamp.from(now), adminId, adminName, summary,
                    idempotencyKey, requestHash, replacementEnforcementActionId,
                    originalEnforcementVersion, targetVersion, Timestamp.from(now), appealId, version,
                    expectedStatus.name()) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    boolean insertPreview(ResolutionPreviewRow preview) {
        try {
            return jdbc.update("""
                    insert into appeal_resolution_previews (
                        preview_token, appeal_id, executor_admin_id, request_fingerprint,
                        owner_confirmation_token, expires_at, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """, preview.token(), preview.appealId(), preview.executorAdminId(),
                    preview.requestFingerprint(), preview.ownerConfirmationToken(),
                    Timestamp.from(preview.expiresAt()), Timestamp.from(preview.createdAt())) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    Optional<ResolutionPreviewRow> preview(String token, String appealId, String executorAdminId) {
        return jdbc.query("""
                select preview_token, appeal_id, executor_admin_id, request_fingerprint,
                       owner_confirmation_token, expires_at, created_at
                from appeal_resolution_previews
                where preview_token=? and appeal_id=? and executor_admin_id=?
                for update
                """, (rs, row) -> new ResolutionPreviewRow(rs.getString("preview_token"),
                rs.getString("appeal_id"), rs.getString("executor_admin_id"),
                rs.getString("request_fingerprint"), rs.getString("owner_confirmation_token"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant()),
                token, appealId, executorAdminId).stream().findFirst();
    }

    boolean insertNote(NoteRow note) {
        try {
            return jdbc.update("""
                    insert into appeal_review_notes
                    (id, appeal_id, body, author_admin_id, author_display_name, correlation_id, idempotency_key, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, note.id(), note.appealId(), note.body(), note.adminId(), note.adminName(),
                    note.correlationId(), note.idempotencyKey(), Timestamp.from(note.createdAt())) == 1;
        } catch (DuplicateKeyException exception) { return false; }
    }

    Optional<NoteRow> noteByRetry(String appealId, String adminId, String key) {
        return jdbc.query("""
                select id, appeal_id, body, author_admin_id, author_display_name, correlation_id,
                       idempotency_key, created_at from appeal_review_notes
                where appeal_id=? and author_admin_id=? and idempotency_key=?
                """, this::note, appealId, adminId, key).stream().findFirst();
    }

    List<NoteRow> notes(String appealId) {
        return jdbc.query("""
                select id, appeal_id, body, author_admin_id, author_display_name, correlation_id,
                       idempotency_key, created_at from appeal_review_notes
                where appeal_id=? order by created_at, id
                """, this::note, appealId);
    }

    void insertEvent(EventRow event) {
        jdbc.update("""
                insert into appeal_events (
                    event_id, appeal_id, event_type, occurred_at, actor_type, actor_id,
                    actor_display_name, source, previous_state, new_state, reason_code, reason,
                    correlation_id, request_id, safe_metadata
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json))
                """, event.eventId(), event.appealId(), event.eventType(), Timestamp.from(event.occurredAt()),
                event.actorType(), event.actorId(), event.actorName(), event.source(), event.previousState(),
                event.newState(), event.reasonCode(), event.reason(), event.correlationId(), event.requestId(),
                json(event.safeMetadata()));
    }

    List<EventRow> events(String appealId) {
        return jdbc.query("""
                select event_id, appeal_id, event_type, occurred_at, actor_type, actor_id,
                       actor_display_name, source, previous_state, new_state, reason_code, reason,
                       correlation_id, request_id, safe_metadata
                from appeal_events where appeal_id=? order by occurred_at, event_id
                """, this::event, appealId);
    }

    Optional<AdminRow> admin(String id) {
        if (id == null) return Optional.empty();
        return jdbc.query("select id, coalesce(nullif(display_name,''),'Platform administrator') display_name from users where id=?",
                (rs, row) -> new AdminRow(rs.getString("id"), rs.getString("display_name")), id).stream().findFirst();
    }

    String userLabel(String id) {
        return jdbc.query("select coalesce(nullif(display_name,''),'Marketplace user') from users where id=?",
                (rs, row) -> rs.getString(1), id).stream().findFirst().orElse("Marketplace user");
    }

    JsonNode currentTarget(TargetType type, String id) {
        if (type == TargetType.USER) {
            return jdbc.query("select id, coalesce(nullif(display_name,''),'Marketplace user') label, status, version from users where id=?",
                    (rs, row) -> (JsonNode) mapper.valueToTree(Map.of("id", rs.getString("id"), "label", rs.getString("label"),
                            "status", rs.getString("status"), "version", rs.getLong("version"))), id)
                    .stream().findFirst().orElse(mapper.valueToTree(Map.of("status", "UNAVAILABLE")));
        }
        return jdbc.query("select b.id, coalesce(nullif(s.name,''),b.legal_name) label, b.status, b.version from businesses b left join stores s on s.business_id=b.id where b.id=?",
                (rs, row) -> (JsonNode) mapper.valueToTree(Map.of("id", rs.getString("id"), "label", rs.getString("label"),
                        "status", rs.getString("status"), "version", rs.getLong("version"))), id)
                .stream().findFirst().orElse(mapper.valueToTree(Map.of("status", "UNAVAILABLE")));
    }

    Optional<CaseRow> caseSummary(String caseId) {
        if (caseId == null) return Optional.empty();
        return jdbc.query("""
                select id, title, status, severity, closed_at, conclusion_code
                from investigation_cases where id=?
                """, (rs, row) -> new CaseRow(rs.getString("id"), rs.getString("title"), rs.getString("status"),
                rs.getString("severity"), instant(rs, "closed_at"), rs.getString("conclusion_code")), caseId)
                .stream().findFirst();
    }

    List<ReportRow> caseReports(String caseId) {
        if (caseId == null) return List.of();
        return jdbc.query("""
                select r.id, r.reason_code, r.severity, r.status, r.safe_target_label, r.created_at
                from investigation_case_reports cr join reports r on r.id=cr.report_id
                where cr.case_id=? order by r.created_at, r.id
                """, (rs, row) -> new ReportRow(rs.getString("id"), rs.getString("reason_code"),
                rs.getString("severity"), rs.getString("status"), rs.getString("safe_target_label"),
                rs.getTimestamp("created_at").toInstant()), caseId);
    }

    JsonNode originalSnapshot(String caseId) {
        if (caseId == null) return mapper.nullNode();
        return jdbc.query("""
                select r.target_snapshot from investigation_case_reports cr
                join reports r on r.id=cr.report_id where cr.case_id=? order by cr.linked_at, r.id limit 1
                """, (rs, row) -> tree(rs.getString(1)), caseId).stream().findFirst().orElse(mapper.nullNode());
    }

    List<EventRow> caseEvents(String caseId) {
        if (caseId == null) return List.of();
        return jdbc.query("""
                select event_id, case_id appeal_id, event_type, occurred_at, 'PLATFORM_ADMIN' actor_type,
                       actor_id, actor_display_name, 'HUMAN_ADMIN' source, previous_state, new_state,
                       reason_code, reason, correlation_id, request_id, safe_metadata
                from investigation_case_events where case_id=? order by occurred_at, event_id
                """, this::event, caseId);
    }

    List<EventRow> localEnforcementEvents(String actionId) {
        return jdbc.query("""
                select event_id, enforcement_action_id appeal_id, event_type, occurred_at, actor_type,
                       actor_id, actor_display_name, source, previous_state, new_state, reason_code, reason,
                       correlation_id, request_id, safe_metadata
                from enforcement_events where enforcement_action_id=? order by occurred_at, event_id
                """, this::event, actionId);
    }

    Set<Scope> replacementScopes(String appealId) {
        return new LinkedHashSet<>(jdbc.queryForList(
                "select scope from appeal_replacement_scopes where appeal_id=? order by scope",
                String.class, appealId).stream().map(Scope::valueOf).toList());
    }

    private AppealRow appeal(ResultSet rs, int row) throws SQLException {
        return new AppealRow(rs.getString("id"), rs.getString("enforcement_action_id"),
                TargetType.valueOf(rs.getString("target_type")), rs.getString("target_id"),
                rs.getString("safe_target_label"), AppellantType.valueOf(rs.getString("appellant_type")),
                rs.getString("appellant_user_id"), Status.valueOf(rs.getString("status")),
                ReasonCode.valueOf(rs.getString("reason_code")), rs.getString("explanation"),
                list(rs.getString("safe_evidence_references")), rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("assigned_admin_id"), instant(rs, "review_started_at"), instant(rs, "reviewed_at"),
                enumValue(ReviewOutcome.class, rs.getString("review_outcome")), rs.getString("review_reason_code"),
                rs.getString("review_reason"), enumValue(ActionType.class, rs.getString("replacement_action_type")),
                instant(rs, "replacement_expires_at"), rs.getString("replacement_reason_code"),
                rs.getString("replacement_reason"), nullableLong(rs, "replacement_expected_target_version"),
                rs.getString("original_case_id"), rs.getLong("original_enforcement_version"),
                rs.getString("correlation_id"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                instant(rs, "resolved_at"), rs.getString("resolved_by_admin_id"),
                rs.getString("resolved_by_admin_display_name"), rs.getString("resolution_summary"),
                rs.getString("resolution_idempotency_key"), rs.getString("resolution_request_hash"),
                rs.getString("replacement_enforcement_action_id"),
                nullableLong(rs, "resolution_original_enforcement_version"),
                nullableLong(rs, "resolution_target_version"));
    }

    private EnforcementRow enforcement(ResultSet rs, int row) throws SQLException {
        Instant now = Instant.now();
        Instant expires = instant(rs, "expires_at");
        Instant revoked = instant(rs, "revoked_at");
        String lifecycle = revoked != null ? "REVOKED" : expires != null && !expires.isAfter(now) ? "EXPIRED" : "ACTIVE";
        return new EnforcementRow(rs.getString("id"), TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"), rs.getString("safe_label"), ActionType.valueOf(rs.getString("action_type")),
                Set.of(), lifecycle, rs.getTimestamp("effective_at").toInstant(), expires, rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("reason_code"), rs.getString("reason"),
                rs.getString("case_id"));
    }

    private EnforcementRow withScopes(EnforcementRow row) {
        Set<Scope> scopes = new LinkedHashSet<>(jdbc.queryForList(
                "select scope from enforcement_action_scopes where enforcement_action_id=? order by scope",
                String.class, row.id()).stream().map(Scope::valueOf).toList());
        return row.withScopes(scopes);
    }

    private NoteRow note(ResultSet rs, int row) throws SQLException {
        return new NoteRow(rs.getString("id"), rs.getString("appeal_id"), rs.getString("body"),
                rs.getString("author_admin_id"), rs.getString("author_display_name"),
                rs.getString("correlation_id"), rs.getString("idempotency_key"),
                rs.getTimestamp("created_at").toInstant());
    }
    private EventRow event(ResultSet rs, int row) throws SQLException {
        return new EventRow(rs.getString("event_id"), rs.getString("appeal_id"), rs.getString("event_type"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_type"), rs.getString("actor_id"),
                rs.getString("actor_display_name"), rs.getString("source"), rs.getString("previous_state"),
                rs.getString("new_state"), rs.getString("reason_code"), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("request_id"), map(rs.getString("safe_metadata")));
    }

    private static void addEnum(StringBuilder sql, List<Object> params, String column, Enum<?> value) {
        if (value != null) { sql.append(" and ").append(column).append("=?\n"); params.add(value.name()); }
    }
    private static String sortSql(String sort) {
        return switch (sort) {
            case "submittedAt,asc" -> "a.submitted_at asc, a.id asc";
            case "updatedAt,desc" -> "a.updated_at desc, a.id desc";
            case "updatedAt,asc" -> "a.updated_at asc, a.id asc";
            default -> "a.submitted_at desc, a.id desc";
        };
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private List<String> list(String value) { try { return value == null ? List.of() : mapper.readValue(value, STRING_LIST); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private Map<String,String> map(String value) { try { return value == null ? Map.of() : mapper.readValue(value, STRING_MAP); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private JsonNode tree(String value) { try { return mapper.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant(); }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException { long value=rs.getLong(column); return rs.wasNull()?null:value; }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) { return value==null?null:Enum.valueOf(type,value); }

    private static final String APPEAL_SELECT = """
            select a.id, a.enforcement_action_id, a.target_type, a.target_id, a.safe_target_label,
                   a.appellant_type, a.appellant_user_id, a.status, a.reason_code, a.explanation,
                   a.safe_evidence_references, a.submitted_at, a.assigned_admin_id, a.review_started_at,
                   a.reviewed_at, a.resolved_at, a.resolved_by_admin_id,
                   a.resolved_by_admin_display_name, a.resolution_summary,
                   a.resolution_idempotency_key, a.resolution_request_hash,
                   a.replacement_enforcement_action_id, a.resolution_original_enforcement_version,
                   a.resolution_target_version, a.review_outcome, a.review_reason_code, a.review_reason,
                   a.replacement_action_type, a.replacement_expires_at, a.replacement_reason_code,
                   a.replacement_reason, a.replacement_expected_target_version, a.original_case_id,
                   a.original_enforcement_version, a.correlation_id, a.version, a.created_at, a.updated_at
            from appeals a
            """;
    private static final String LOCAL_ENFORCEMENT_SELECT = """
            select ea.id, ea.target_type, ea.target_id, ea.action_type, ea.version, ea.effective_at,
                   ea.expires_at, ea.revoked_at, ea.created_at, ea.reason_code, ea.reason, ea.case_id,
                   case when ea.target_type='USER' then coalesce(nullif(u.display_name,''),'Marketplace user')
                        else coalesce(nullif(s.name,''),b.legal_name,'Marketplace business') end safe_label
            from enforcement_actions ea
            left join users u on ea.target_type='USER' and u.id=ea.target_id
            left join businesses b on ea.target_type='BUSINESS' and b.id=ea.target_id
            left join stores s on s.business_id=b.id
            """;

    record Search(String query, TargetType targetType, Status status, ReasonCode reasonCode,
                  Assignment assignment, Instant submittedFrom, Instant submittedTo,
                  int page, int size, String sort, String adminId) { }
    record SearchResult(List<AppealRow> rows, long total) { }
    record AppealRow(String id, String enforcementActionId, TargetType targetType, String targetId,
                     String safeTargetLabel, AppellantType appellantType, String appellantUserId,
                     Status status, ReasonCode reasonCode, String explanation, List<String> evidenceReferences,
                     Instant submittedAt, String assignedAdminId, Instant reviewStartedAt, Instant reviewedAt,
                     ReviewOutcome reviewOutcome, String reviewReasonCode, String reviewReason,
                     ActionType replacementActionType, Instant replacementExpiresAt,
                     String replacementReasonCode, String replacementReason, Long replacementTargetVersion,
                     String originalCaseId, long originalEnforcementVersion, String correlationId,
                     long version, Instant createdAt, Instant updatedAt, Instant resolvedAt,
                     String resolvedByAdminId, String resolvedByAdminName, String resolutionSummary,
                     String resolutionIdempotencyKey, String resolutionRequestHash,
                     String replacementEnforcementActionId, Long resolutionOriginalEnforcementVersion,
                     Long resolutionTargetVersion) { }
    record EnforcementRow(String id, TargetType targetType, String targetId, String safeLabel,
                          ActionType actionType, Set<Scope> scopes, String lifecycle, Instant effectiveAt,
                          Instant expiresAt, long version, Instant createdAt, String reasonCode,
                          String reason, String caseId) {
        EnforcementRow withScopes(Set<Scope> values) { return new EnforcementRow(id,targetType,targetId,safeLabel,
                actionType,Set.copyOf(values),lifecycle,effectiveAt,expiresAt,version,createdAt,reasonCode,reason,caseId); }
    }
    record NoteRow(String id, String appealId, String body, String adminId, String adminName,
                   String correlationId, String idempotencyKey, Instant createdAt) { }
    record EventRow(String eventId, String appealId, String eventType, Instant occurredAt, String actorType,
                    String actorId, String actorName, String source, String previousState, String newState,
                    String reasonCode, String reason, String correlationId, String requestId,
                    Map<String,String> safeMetadata) { }
    record AdminRow(String id, String displayName) { }
    record CaseRow(String id, String title, String status, String severity, Instant closedAt, String conclusionCode) { }
    record ReportRow(String id, String reasonCode, String severity, String status, String label, Instant createdAt) { }
    record ResolutionPreviewRow(String token, String appealId, String executorAdminId,
                                String requestFingerprint, String ownerConfirmationToken,
                                Instant expiresAt, Instant createdAt) { }
}
