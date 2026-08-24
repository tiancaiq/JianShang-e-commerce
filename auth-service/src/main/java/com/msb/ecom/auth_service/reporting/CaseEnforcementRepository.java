package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.*;

@Repository
@RequiredArgsConstructor
class CaseEnforcementRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    void insert(ProposalRow value) {
        jdbc.update("""
                insert into case_enforcement_proposals (
                    id, case_id, target_type, target_id, action_type, reason_code, reason,
                    effective_at, expires_at, expected_target_version, status, created_by_admin_id,
                    created_at, updated_at, version, correlation_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, 0, ?)
                """, value.id(), value.caseId(), value.targetType().name(), value.targetId(),
                value.actionType().name(), value.reasonCode(), value.reason(), timestamp(value.effectiveAt()),
                timestamp(value.expiresAt()), value.expectedTargetVersion(), value.createdByAdminId(),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()), value.correlationId());
        replaceScopes(value.id(), value.scopes());
    }

    List<ProposalRow> list(String caseId) {
        return jdbc.query(SELECT + " where p.case_id = ? order by p.created_at, p.id", this::row, caseId);
    }

    Optional<ProposalRow> find(String caseId, String proposalId) {
        return jdbc.query(SELECT + " where p.case_id = ? and p.id = ?", this::row, caseId, proposalId)
                .stream().findFirst();
    }

    boolean update(ProposalRow value, long expectedVersion, Instant now) {
        int updated = jdbc.update("""
                update case_enforcement_proposals set target_type=?, target_id=?, action_type=?, reason_code=?,
                    reason=?, effective_at=?, expires_at=?, expected_target_version=?, status='DRAFT',
                    dry_run_validated_at=null, dry_run_target_version=null, dry_run_result=null,
                    execution_error_code=null, execution_error_summary=null, version=version+1, updated_at=?
                where case_id=? and id=? and version=? and status in ('DRAFT','VALIDATED','FAILED')
                """, value.targetType().name(), value.targetId(), value.actionType().name(), value.reasonCode(),
                value.reason(), timestamp(value.effectiveAt()), timestamp(value.expiresAt()),
                value.expectedTargetVersion(), Timestamp.from(now), value.caseId(), value.id(), expectedVersion);
        if (updated == 1) replaceScopes(value.id(), value.scopes());
        return updated == 1;
    }

    boolean validated(String caseId, String proposalId, long expectedVersion, long targetVersion,
                      JsonNode result, Instant now) {
        return jdbc.update("""
                update case_enforcement_proposals set status='VALIDATED', dry_run_validated_at=?,
                    dry_run_target_version=?, dry_run_result=cast(? as json), execution_error_code=null,
                    execution_error_summary=null, version=version+1, updated_at=?
                where case_id=? and id=? and version=? and status in ('DRAFT','VALIDATED','FAILED')
                """, Timestamp.from(now), targetVersion, json(result), Timestamp.from(now), caseId, proposalId,
                expectedVersion) == 1;
    }

    boolean dryRunFailed(String caseId, String proposalId, long expectedVersion, String code,
                         String summary, Instant now) {
        return jdbc.update("""
                update case_enforcement_proposals set status='DRAFT', dry_run_validated_at=null,
                    dry_run_target_version=null, dry_run_result=null, execution_error_code=?,
                    execution_error_summary=?, version=version+1, updated_at=?
                where case_id=? and id=? and version=? and status in ('DRAFT','VALIDATED','FAILED')
                """, code, summary, Timestamp.from(now), caseId, proposalId, expectedVersion) == 1;
    }

    boolean cancel(String caseId, String proposalId, long expectedVersion, Instant now) {
        return jdbc.update("""
                update case_enforcement_proposals set status='CANCELLED', version=version+1, updated_at=?
                where case_id=? and id=? and version=? and status in ('DRAFT','VALIDATED','FAILED')
                """, Timestamp.from(now), caseId, proposalId, expectedVersion) == 1;
    }

    boolean pinExecution(String caseId, String proposalId, long expectedVersion, String key, Instant now) {
        return jdbc.update("""
                update case_enforcement_proposals set execution_idempotency_key=coalesce(execution_idempotency_key,?),
                    execution_error_code=null, execution_error_summary=null, version=version+1, updated_at=?
                where case_id=? and id=? and version=? and status in ('VALIDATED','FAILED')
                  and (execution_idempotency_key is null or execution_idempotency_key=?)
                """, key, Timestamp.from(now), caseId, proposalId, expectedVersion, key) == 1;
    }

    boolean executed(String caseId, String proposalId, long expectedVersion, String actionId, Instant now) {
        return jdbc.update("""
                update case_enforcement_proposals set status='EXECUTED', resulting_enforcement_action_id=?,
                    execution_error_code=null, execution_error_summary=null, version=version+1, updated_at=?
                where case_id=? and id=? and version=? and status in ('VALIDATED','FAILED')
                """, actionId, Timestamp.from(now), caseId, proposalId, expectedVersion) == 1;
    }

    boolean failed(String caseId, String proposalId, long expectedVersion, String code, String summary, Instant now) {
        return jdbc.update("""
                update case_enforcement_proposals set status='FAILED', execution_error_code=?,
                    execution_error_summary=?, version=version+1, updated_at=?
                where case_id=? and id=? and version=? and status in ('VALIDATED','FAILED')
                """, code, summary, Timestamp.from(now), caseId, proposalId, expectedVersion) == 1;
    }

    void link(LinkRow value) {
        jdbc.update("""
                insert into case_enforcement_links (case_id, proposal_id, target_type, target_id,
                    enforcement_action_id, executed_at, executed_by_admin_id, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, value.caseId(), value.proposalId(), value.targetType().name(), value.targetId(),
                value.enforcementActionId(), Timestamp.from(value.executedAt()), value.executedByAdminId(),
                value.correlationId());
    }

    List<LinkRow> links(String caseId) {
        return jdbc.query("""
                select case_id, proposal_id, target_type, target_id, enforcement_action_id,
                       executed_at, executed_by_admin_id, correlation_id
                from case_enforcement_links where case_id=? order by executed_at, proposal_id
                """, (rs, n) -> new LinkRow(rs.getString("case_id"), rs.getString("proposal_id"),
                ReportContracts.TargetType.valueOf(rs.getString("target_type")), rs.getString("target_id"),
                rs.getString("enforcement_action_id"), rs.getTimestamp("executed_at").toInstant(),
                rs.getString("executed_by_admin_id"), rs.getString("correlation_id")), caseId);
    }

    long executedCount(String caseId) {
        Long value = jdbc.queryForObject("select count(*) from case_enforcement_proposals where case_id=? and status='EXECUTED'",
                Long.class, caseId);
        return value == null ? 0 : value;
    }

    long unresolvedCount(String caseId) {
        Long value = jdbc.queryForObject("select count(*) from case_enforcement_proposals where case_id=? and status not in ('EXECUTED','CANCELLED')",
                Long.class, caseId);
        return value == null ? 0 : value;
    }

    private void replaceScopes(String proposalId, Set<Scope> scopes) {
        jdbc.update("delete from case_enforcement_proposal_scopes where proposal_id=?", proposalId);
        scopes.stream().sorted().forEach(scope -> jdbc.update(
                "insert into case_enforcement_proposal_scopes (proposal_id, scope) values (?, ?)",
                proposalId, scope.name()));
    }

    private ProposalRow row(ResultSet rs, int ignored) throws SQLException {
        String id = rs.getString("id");
        Set<Scope> scopes = new LinkedHashSet<>(jdbc.queryForList(
                "select scope from case_enforcement_proposal_scopes where proposal_id=? order by scope",
                String.class, id).stream().map(Scope::valueOf).toList());
        return new ProposalRow(id, rs.getString("case_id"),
                ReportContracts.TargetType.valueOf(rs.getString("target_type")), rs.getString("target_id"),
                ActionType.valueOf(rs.getString("action_type")), Set.copyOf(scopes), rs.getString("reason_code"),
                rs.getString("reason"), instant(rs, "effective_at"), instant(rs, "expires_at"),
                rs.getLong("expected_target_version"), ProposalStatus.valueOf(rs.getString("status")),
                rs.getString("created_by_admin_id"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"),
                instant(rs, "dry_run_validated_at"), nullableLong(rs, "dry_run_target_version"),
                tree(rs.getString("dry_run_result")), rs.getString("resulting_enforcement_action_id"),
                rs.getString("execution_idempotency_key"), rs.getString("execution_error_code"),
                rs.getString("execution_error_summary"), rs.getString("correlation_id"));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private JsonNode tree(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }

    private static final String SELECT = """
            select p.id, p.case_id, p.target_type, p.target_id, p.action_type, p.reason_code, p.reason,
                   p.effective_at, p.expires_at, p.expected_target_version, p.status, p.created_by_admin_id,
                   p.created_at, p.updated_at, p.version, p.dry_run_validated_at, p.dry_run_target_version,
                   p.dry_run_result, p.resulting_enforcement_action_id, p.execution_idempotency_key,
                   p.execution_error_code, p.execution_error_summary, p.correlation_id
            from case_enforcement_proposals p
            """;

    record ProposalRow(String id, String caseId, ReportContracts.TargetType targetType, String targetId,
                       ActionType actionType, Set<Scope> scopes, String reasonCode, String reason,
                       Instant effectiveAt, Instant expiresAt, long expectedTargetVersion, ProposalStatus status,
                       String createdByAdminId, Instant createdAt, Instant updatedAt, long version,
                       Instant dryRunValidatedAt, Long dryRunTargetVersion, JsonNode dryRunResult,
                       String resultingEnforcementActionId, String executionIdempotencyKey,
                       String executionErrorCode, String executionErrorSummary, String correlationId) { }
    record LinkRow(String caseId, String proposalId, ReportContracts.TargetType targetType, String targetId,
                   String enforcementActionId, Instant executedAt, String executedByAdminId,
                   String correlationId) { }
}
