package com.msb.ecom.auth_service.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
class GovernanceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    long governanceVersion(boolean lock) {
        return jdbc.queryForObject("select version from admin_governance_state where id = 'ADMIN_AUTHORITY'"
                + (lock ? " for update" : ""), Long.class);
    }

    boolean bumpGovernanceVersion(long expectedVersion, Instant now) {
        return jdbc.update("""
                update admin_governance_state
                set version = version + 1, updated_at = ?
                where id = 'ADMIN_AUTHORITY' and version = ?
                """, Timestamp.from(now), expectedVersion) == 1;
    }

    Optional<UserRow> user(String userId) {
        return jdbc.query("""
                select id, coalesce(nullif(trim(display_name), ''), 'Marketplace user') display_name,
                       status, account_type
                from users where id = ?
                """, (rs, n) -> new UserRow(rs.getString("id"), rs.getString("display_name"),
                        rs.getString("status"), rs.getString("account_type")), userId).stream().findFirst();
    }

    List<AdminRow> admins(String q, String role, String status, Boolean temporary,
                          int page, int size, Instant now) {
        StringBuilder where = new StringBuilder(" where exists (select 1 from admin_role_assignments history where history.admin_user_id = u.id) ");
        List<Object> params = new ArrayList<>();
        if (q != null) {
            where.append(" and (lower(u.display_name) like ? escape '!' or u.id = ?) ");
            params.add("%" + escape(q.toLowerCase()) + "%");
            params.add(q);
        }
        if (role != null) {
            where.append("""
                    and exists (select 1 from admin_role_assignments role_assignment
                                where role_assignment.admin_user_id = u.id
                                  and role_assignment.role_id = ? and role_assignment.status = 'ACTIVE'
                                  and role_assignment.effective_at <= ?
                                  and (role_assignment.expires_at is null or role_assignment.expires_at > ?))
                    """);
            params.add(role); params.add(Timestamp.from(now)); params.add(Timestamp.from(now));
        }
        if (status != null) {
            where.append("ADMIN_ENABLED".equals(status) ? """
                    and exists (select 1 from admin_role_assignments effective
                                where effective.admin_user_id = u.id and effective.status = 'ACTIVE'
                                  and effective.effective_at <= ?
                                  and (effective.expires_at is null or effective.expires_at > ?))
                    """ : """
                    and not exists (select 1 from admin_role_assignments effective
                                    where effective.admin_user_id = u.id and effective.status = 'ACTIVE'
                                      and effective.effective_at <= ?
                                      and (effective.expires_at is null or effective.expires_at > ?))
                    """);
            params.add(Timestamp.from(now)); params.add(Timestamp.from(now));
        }
        if (temporary != null) {
            where.append(temporary ? """
                    and exists (select 1 from admin_role_assignments temporary
                                where temporary.admin_user_id = u.id and temporary.status = 'ACTIVE'
                                  and temporary.expires_at is not null and temporary.effective_at <= ?
                                  and temporary.expires_at > ?)
                    """ : """
                    and not exists (select 1 from admin_role_assignments temporary
                                    where temporary.admin_user_id = u.id and temporary.status = 'ACTIVE'
                                      and temporary.expires_at is not null and temporary.effective_at <= ?
                                      and temporary.expires_at > ?)
                    """);
            params.add(Timestamp.from(now)); params.add(Timestamp.from(now));
        }
        String sql = """
                select u.id, coalesce(nullif(trim(u.display_name), ''), 'Marketplace user') display_name,
                       u.status,
                       exists(select 1 from admin_role_assignments effective
                              where effective.admin_user_id = u.id and effective.status = 'ACTIVE'
                                and effective.effective_at <= ?
                                and (effective.expires_at is null or effective.expires_at > ?)) enabled,
                       exists(select 1 from admin_role_assignments temporary
                              where temporary.admin_user_id = u.id and temporary.status = 'ACTIVE'
                                and temporary.expires_at is not null and temporary.effective_at <= ?
                                and temporary.expires_at > ?) temporary,
                       (select max(event.created_at) from admin_governance_events event
                        where event.subject_admin_id = u.id) last_change
                from users u
                """ + where + " order by coalesce(u.display_name, u.id), u.id limit ? offset ?";
        List<Object> queryParams = new ArrayList<>();
        queryParams.add(Timestamp.from(now)); queryParams.add(Timestamp.from(now));
        queryParams.add(Timestamp.from(now)); queryParams.add(Timestamp.from(now));
        queryParams.addAll(params); queryParams.add(size); queryParams.add(page * size);
        return jdbc.query(sql, (rs, n) -> new AdminRow(rs.getString("id"), rs.getString("display_name"),
                rs.getString("status"), rs.getBoolean("enabled"), rs.getBoolean("temporary"),
                instant(rs, "last_change")), queryParams.toArray());
    }

    long adminCount(String q, String role, String status, Boolean temporary, Instant now) {
        StringBuilder where = new StringBuilder(" where exists (select 1 from admin_role_assignments history where history.admin_user_id = u.id) ");
        List<Object> params = new ArrayList<>();
        if (q != null) {
            where.append(" and (lower(u.display_name) like ? escape '!' or u.id = ?) ");
            params.add("%" + escape(q.toLowerCase()) + "%"); params.add(q);
        }
        if (role != null) {
            where.append("""
                    and exists (select 1 from admin_role_assignments role_assignment
                                where role_assignment.admin_user_id = u.id
                                  and role_assignment.role_id = ? and role_assignment.status = 'ACTIVE'
                                  and role_assignment.effective_at <= ?
                                  and (role_assignment.expires_at is null or role_assignment.expires_at > ?))
                    """);
            params.add(role); params.add(Timestamp.from(now)); params.add(Timestamp.from(now));
        }
        if (status != null) {
            where.append("ADMIN_ENABLED".equals(status) ? """
                    and exists (select 1 from admin_role_assignments effective
                                where effective.admin_user_id = u.id and effective.status = 'ACTIVE'
                                  and effective.effective_at <= ?
                                  and (effective.expires_at is null or effective.expires_at > ?))
                    """ : """
                    and not exists (select 1 from admin_role_assignments effective
                                    where effective.admin_user_id = u.id and effective.status = 'ACTIVE'
                                      and effective.effective_at <= ?
                                      and (effective.expires_at is null or effective.expires_at > ?))
                    """);
            params.add(Timestamp.from(now)); params.add(Timestamp.from(now));
        }
        if (temporary != null) {
            where.append(temporary ? """
                    and exists (select 1 from admin_role_assignments temporary
                                where temporary.admin_user_id = u.id and temporary.status = 'ACTIVE'
                                  and temporary.expires_at is not null and temporary.effective_at <= ?
                                  and temporary.expires_at > ?)
                    """ : """
                    and not exists (select 1 from admin_role_assignments temporary
                                    where temporary.admin_user_id = u.id and temporary.status = 'ACTIVE'
                                      and temporary.expires_at is not null and temporary.effective_at <= ?
                                      and temporary.expires_at > ?)
                    """);
            params.add(Timestamp.from(now)); params.add(Timestamp.from(now));
        }
        Long count = jdbc.queryForObject("select count(*) from users u " + where,
                Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    List<String> effectiveRoles(String userId, Instant now) {
        return jdbc.queryForList("""
                select distinct role_id from admin_role_assignments
                where admin_user_id = ? and status = 'ACTIVE' and effective_at <= ?
                  and (expires_at is null or expires_at > ?)
                order by role_id
                """, String.class, userId, Timestamp.from(now), Timestamp.from(now));
    }

    // Loads effective roles for a whole admin page without one query per administrator.
    Map<String, List<String>> effectiveRoles(List<String> userIds, Instant now) {
        if (userIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(userIds);
        parameters.add(Timestamp.from(now));
        parameters.add(Timestamp.from(now));
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbc.query("""
                select distinct admin_user_id, role_id
                from admin_role_assignments
                where admin_user_id in (%s) and status = 'ACTIVE' and effective_at <= ?
                  and (expires_at is null or expires_at > ?)
                order by admin_user_id, role_id
                """.formatted(placeholders), (rs, row) -> Map.entry(
                        rs.getString("admin_user_id"), rs.getString("role_id")), parameters.toArray())
                .forEach(role -> result.computeIfAbsent(role.getKey(), ignored -> new ArrayList<>()).add(role.getValue()));
        return result;
    }

    List<String> effectivePermissions(String userId, Instant now) {
        return jdbc.queryForList("""
                select distinct mapping.permission_id
                from admin_role_assignments assignment
                join admin_role_permissions mapping on mapping.role_id = assignment.role_id
                where assignment.admin_user_id = ? and assignment.status = 'ACTIVE'
                  and assignment.effective_at <= ?
                  and (assignment.expires_at is null or assignment.expires_at > ?)
                order by mapping.permission_id
                """, String.class, userId, Timestamp.from(now), Timestamp.from(now));
    }

    List<RoleRow> roles() {
        return jdbc.query("""
                select role.id, role.description, permission.permission_id
                from roles role
                left join admin_role_permissions permission on permission.role_id = role.id
                where role.id in (
                    'SUPER_ADMIN','TRUST_AND_SAFETY_ADMIN','BUSINESS_REVIEWER','LISTING_MODERATOR',
                    'SUPPORT_ADMIN','USER_RESTRICTOR','BUSINESS_RESTRICTOR','CATALOG_ADMIN',
                    'OPERATIONS_ADMIN','GOVERNANCE_ADMIN','AUDITOR','AI_ADMIN_AGENT'
                )
                order by role.id, permission.permission_id
                """, (rs, n) -> new RoleRow(rs.getString("id"), rs.getString("description"),
                        rs.getString("permission_id")));
    }

    List<AssignmentRow> assignments(String userId, boolean lock) {
        return jdbc.query("""
                select id, admin_user_id, role_id, status, effective_at, expires_at,
                       granted_by_admin_id, reason, revoked_at, revoked_by_admin_id,
                       revocation_reason, request_hash, revocation_idempotency_key,
                       revocation_request_hash, version, created_at
                from admin_role_assignments where admin_user_id = ?
                order by created_at desc, id desc
                """ + (lock ? " for update" : ""), this::assignment, userId);
    }

    Optional<AssignmentRow> assignment(String id, boolean lock) {
        return jdbc.query("""
                select id, admin_user_id, role_id, status, effective_at, expires_at,
                       granted_by_admin_id, reason, revoked_at, revoked_by_admin_id,
                       revocation_reason, request_hash, revocation_idempotency_key,
                       revocation_request_hash, version, created_at
                from admin_role_assignments where id = ?
                """ + (lock ? " for update" : ""), this::assignment, id).stream().findFirst();
    }

    boolean overlappingAssignment(String userId, String role, Instant effectiveAt, Instant expiresAt) {
        Timestamp end = expiresAt == null ? null : Timestamp.from(expiresAt);
        Long count = jdbc.queryForObject("""
                select count(*) from admin_role_assignments
                where admin_user_id = ? and role_id = ? and status = 'ACTIVE'
                  and (expires_at is null or expires_at > ?)
                  and (? is null or effective_at < ?)
                """, Long.class, userId, role, Timestamp.from(effectiveAt), end, end);
        return count != null && count > 0;
    }

    void insertAssignment(AssignmentInsert value) {
        jdbc.update("""
                insert into admin_role_assignments (
                    id, admin_user_id, role_id, status, effective_at, expires_at,
                    granted_by_admin_id, reason, revoked_at, revoked_by_admin_id,
                    revocation_reason, request_idempotency_key, request_hash,
                    correlation_id, version, created_at, updated_at
                ) values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, null, null, null, ?, ?, ?, 0, ?, ?)
                """, value.id(), value.userId(), value.role(), Timestamp.from(value.effectiveAt()),
                timestamp(value.expiresAt()), value.actorId(), value.reason(), value.idempotencyKey(),
                value.requestHash(), value.correlationId(), Timestamp.from(value.now()), Timestamp.from(value.now()));
    }

    boolean revokeAssignment(String id, long expectedVersion, String actorId, String reason,
                             String idempotencyKey, String requestHash, Instant now) {
        return jdbc.update("""
                update admin_role_assignments
                set status = 'REVOKED', revoked_at = ?, revoked_by_admin_id = ?,
                    revocation_reason = ?, revocation_idempotency_key = ?,
                    revocation_request_hash = ?, version = version + 1, updated_at = ?
                where id = ? and version = ? and status = 'ACTIVE'
                """, Timestamp.from(now), actorId, reason, idempotencyKey, requestHash,
                Timestamp.from(now), id, expectedVersion) == 1;
    }

    long effectiveSuperAdmins(Instant now) {
        Long value = jdbc.queryForObject("""
                select count(distinct admin_user_id) from admin_role_assignments
                where role_id = 'SUPER_ADMIN' and status = 'ACTIVE' and effective_at <= ?
                  and (expires_at is null or expires_at > ?)
                """, Long.class, Timestamp.from(now), Timestamp.from(now));
        return value == null ? 0 : value;
    }

    boolean permanentEffectiveSuperAdminExistsExcluding(String assignmentId, Instant now) {
        Long value = jdbc.queryForObject("""
                select count(*) from admin_role_assignments
                where id <> ? and role_id = 'SUPER_ADMIN' and status = 'ACTIVE'
                  and effective_at <= ? and expires_at is null
                """, Long.class, assignmentId, Timestamp.from(now));
        return value != null && value > 0;
    }

    /** Locking current read used inside the serialized authority transaction. */
    boolean permanentEffectiveSuperAdminExistsExcludingLocked(String assignmentId, Instant now) {
        return !jdbc.queryForList("""
                select id from admin_role_assignments
                where id <> ? and role_id = 'SUPER_ADMIN' and status = 'ACTIVE'
                  and effective_at <= ? and expires_at is null
                for update
                """, String.class, assignmentId, Timestamp.from(now)).isEmpty();
    }

    Optional<AssignmentRow> assignmentRequest(String actorId, String key) {
        return jdbc.query("""
                select id, admin_user_id, role_id, status, effective_at, expires_at,
                       granted_by_admin_id, reason, revoked_at, revoked_by_admin_id,
                       revocation_reason, request_hash, revocation_idempotency_key,
                       revocation_request_hash, version, created_at
                from admin_role_assignments
                where granted_by_admin_id = ? and request_idempotency_key = ?
                """, this::assignment, actorId, key).stream().findFirst();
    }

    Optional<PolicyRow> policy(String actionType) {
        return jdbc.query("""
                select action_type, risk_level, approval_mode, required_approvals,
                       requester_may_approve, approval_expires_after_minutes,
                       required_requester_permission, required_approver_permission,
                       threshold_value, threshold_currency, enabled, version
                from sensitive_action_policies where action_type = ?
                """, (rs, n) -> new PolicyRow(rs.getString("action_type"), rs.getString("risk_level"),
                        rs.getString("approval_mode"), rs.getInt("required_approvals"),
                        rs.getBoolean("requester_may_approve"), rs.getInt("approval_expires_after_minutes"),
                        rs.getString("required_requester_permission"), rs.getString("required_approver_permission"),
                        rs.getBigDecimal("threshold_value"), rs.getString("threshold_currency"),
                        rs.getBoolean("enabled"), rs.getLong("version")), actionType).stream().findFirst();
    }

    Optional<ApprovalRow> approval(String id, boolean lock) {
        return jdbc.query(approvalSelect() + " where request.id = ?" + (lock ? " for update" : ""),
                this::approval, id).stream().findFirst();
    }

    Optional<ApprovalRow> approvalRequest(String requester, String action, String key) {
        return jdbc.query(approvalSelect() + " where request.requester_admin_id = ? and request.action_type = ? and request.request_idempotency_key = ?",
                this::approval, requester, action, key).stream().findFirst();
    }

    boolean insertApproval(ApprovalInsert value) {
        try {
            jdbc.update("""
                    insert into admin_approval_requests (
                        id, action_type, requester_admin_id, target_type, target_id,
                        request_payload_fingerprint, safe_action_summary, safe_action_payload,
                        status, risk_level, required_approvals, policy_version,
                        expected_target_version, request_idempotency_key,
                        created_at, expires_at, correlation_id, version, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, cast(? as json), 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                    """, value.id(), value.actionType(), value.requesterId(), value.targetType(), value.targetId(),
                    value.fingerprint(), value.summary(), json(value.payload()), value.riskLevel(),
                    value.requiredApprovals(), value.policyVersion(), value.expectedTargetVersion(),
                    value.idempotencyKey(), Timestamp.from(value.now()), Timestamp.from(value.expiresAt()),
                    value.correlationId(), Timestamp.from(value.now()));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    List<ApprovalRow> approvals(String status, String risk, String action, String requester,
                                String targetType, int page, int size, Instant now) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if ("EXPIRED".equals(status)) {
            where.append(" and request.status in ('PENDING','APPROVED') and request.expires_at <= ? ");
            params.add(Timestamp.from(now));
        } else if (status != null) {
            where.append(" and request.status = ? "); params.add(status);
            if ("PENDING".equals(status) || "APPROVED".equals(status)) {
                where.append(" and request.expires_at > ? "); params.add(Timestamp.from(now));
            }
        }
        if (risk != null) { where.append(" and request.risk_level = ? "); params.add(risk); }
        if (action != null) { where.append(" and request.action_type = ? "); params.add(action); }
        if (requester != null) { where.append(" and request.requester_admin_id = ? "); params.add(requester); }
        if (targetType != null) { where.append(" and request.target_type = ? "); params.add(targetType); }
        params.add(size); params.add(page * size);
        return jdbc.query(approvalSelect() + where + " order by request.created_at desc, request.id desc limit ? offset ?",
                this::approval, params.toArray());
    }

    long approvalCount(String status, String risk, String action, String requester, String targetType,
                       Instant now) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if ("EXPIRED".equals(status)) {
            where.append(" and status in ('PENDING','APPROVED') and expires_at <= ? ");
            params.add(Timestamp.from(now));
        } else if (status != null) {
            where.append(" and status = ? "); params.add(status);
            if ("PENDING".equals(status) || "APPROVED".equals(status)) {
                where.append(" and expires_at > ? "); params.add(Timestamp.from(now));
            }
        }
        if (risk != null) { where.append(" and risk_level = ? "); params.add(risk); }
        if (action != null) { where.append(" and action_type = ? "); params.add(action); }
        if (requester != null) { where.append(" and requester_admin_id = ? "); params.add(requester); }
        if (targetType != null) { where.append(" and target_type = ? "); params.add(targetType); }
        Long count = jdbc.queryForObject("select count(*) from admin_approval_requests" + where,
                Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    Optional<DecisionRow> decisionByKey(String actorId, String key) {
        return jdbc.query("""
                select id, approval_request_id, approver_admin_id, decision, reason,
                       idempotency_key, request_hash, created_at
                from admin_approval_decisions where approver_admin_id = ? and idempotency_key = ?
                """, this::decision, actorId, key).stream().findFirst();
    }

    boolean insertDecision(DecisionInsert value) {
        try {
            jdbc.update("""
                    insert into admin_approval_decisions (
                        id, approval_request_id, approver_admin_id, decision,
                        reason, idempotency_key, request_hash, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, value.id(), value.approvalId(), value.actorId(), value.decision(),
                    value.reason(), value.idempotencyKey(), value.requestHash(), Timestamp.from(value.now()));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    List<DecisionRow> decisions(String approvalId) {
        return jdbc.query("""
                select id, approval_request_id, approver_admin_id, decision, reason,
                       idempotency_key, request_hash, created_at
                from admin_approval_decisions where approval_request_id = ?
                order by created_at, id
                """, this::decision, approvalId);
    }

    int approvalDecisionCount(String approvalId) {
        Integer value = jdbc.queryForObject("""
                select count(*) from admin_approval_decisions
                where approval_request_id = ? and decision = 'APPROVE'
                """, Integer.class, approvalId);
        return value == null ? 0 : value;
    }

    boolean transitionApproval(String id, long expectedVersion, String fromStatus,
                               String toStatus, Instant now) {
        return jdbc.update("""
                update admin_approval_requests set status = ?, version = version + 1, updated_at = ?
                where id = ? and version = ? and status = ?
                """, toStatus, Timestamp.from(now), id, expectedVersion, fromStatus) == 1;
    }

    boolean cancelApproval(String id, long expectedVersion, String requesterId,
                           String idempotencyKey, String requestHash, Instant now) {
        return jdbc.update("""
                update admin_approval_requests
                set status = 'CANCELLED', cancellation_idempotency_key = ?,
                    cancellation_request_hash = ?, version = version + 1, updated_at = ?
                where id = ? and requester_admin_id = ? and version = ? and status = 'PENDING'
                """, idempotencyKey, requestHash, Timestamp.from(now), id, requesterId,
                expectedVersion) == 1;
    }

    boolean startExecution(String id, long expectedVersion, String executorId, String key, Instant now) {
        return jdbc.update("""
                update admin_approval_requests
                set status = 'EXECUTING', executor_admin_id = ?, execution_idempotency_key = ?,
                    version = version + 1, updated_at = ?
                where id = ? and version = ? and status in ('APPROVED','FAILED')
                  and (execution_idempotency_key is null or execution_idempotency_key = ?)
                """, executorId, key, Timestamp.from(now), id, expectedVersion, key) == 1;
    }

    void completeExecution(String id, String reference, Instant now) {
        jdbc.update("""
                update admin_approval_requests
                set status = 'EXECUTED', execution_reference = ?, executed_at = ?,
                    execution_failure_code = null, execution_failure_summary = null,
                    version = version + 1, updated_at = ?
                where id = ? and status = 'EXECUTING'
                """, reference, Timestamp.from(now), Timestamp.from(now), id);
    }

    void failExecution(String id, String code, String summary, Instant now) {
        jdbc.update("""
                update admin_approval_requests
                set status = 'FAILED', execution_failure_code = ?, execution_failure_summary = ?,
                    version = version + 1, updated_at = ?
                where id = ? and status = 'EXECUTING'
                """, code, summary, Timestamp.from(now), id);
    }

    void event(EventInsert value) {
        jdbc.update("""
                insert into admin_governance_events (
                    id, event_type, actor_admin_id, subject_admin_id, approval_request_id,
                    target_type, target_id, reason, outcome, safe_metadata,
                    correlation_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json), ?, ?)
                """, value.id(), value.eventType(), value.actorId(), value.subjectId(), value.approvalId(),
                value.targetType(), value.targetId(), value.reason(), value.outcome(), json(value.metadata()),
                value.correlationId(), Timestamp.from(value.now()));
    }

    List<EventRow> eventsForAdmin(String adminId, int limit) {
        return jdbc.query("""
                select id, event_type, actor_admin_id, subject_admin_id, approval_request_id,
                       target_type, target_id, reason, outcome, safe_metadata,
                       correlation_id, created_at
                from admin_governance_events
                where subject_admin_id = ? or actor_admin_id = ?
                order by created_at desc, id desc limit ?
                """, this::mapEvent, adminId, adminId, limit);
    }

    List<EventRow> eventsForApproval(String approvalId, int limit) {
        return jdbc.query("""
                select id, event_type, actor_admin_id, subject_admin_id, approval_request_id,
                       target_type, target_id, reason, outcome, safe_metadata,
                       correlation_id, created_at
                from admin_governance_events where approval_request_id = ?
                order by created_at, id limit ?
                """, this::mapEvent, approvalId, limit);
    }

    List<EventRow> recentEvents(int limit) {
        return jdbc.query("""
                select id, event_type, actor_admin_id, subject_admin_id, approval_request_id,
                       target_type, target_id, reason, outcome, safe_metadata,
                       correlation_id, created_at
                from admin_governance_events order by created_at desc, id desc limit ?
                """, this::mapEvent, limit);
    }

    long activeAdminCount(Instant now) {
        Long value = jdbc.queryForObject("""
                select count(distinct admin_user_id) from admin_role_assignments
                where status = 'ACTIVE' and effective_at <= ? and (expires_at is null or expires_at > ?)
                """, Long.class, Timestamp.from(now), Timestamp.from(now));
        return value == null ? 0 : value;
    }

    long pendingApprovalCount(Instant now) {
        Long value = jdbc.queryForObject("""
                select count(*) from admin_approval_requests
                where status = 'PENDING' and expires_at > ?
                """, Long.class, Timestamp.from(now));
        return value == null ? 0 : value;
    }

    long temporaryElevationCount(Instant now) {
        Long value = jdbc.queryForObject("""
                select count(*) from admin_role_assignments
                where status = 'ACTIVE' and expires_at is not null
                  and effective_at <= ? and expires_at > ?
                """, Long.class, Timestamp.from(now), Timestamp.from(now));
        return value == null ? 0 : value;
    }

    private String approvalSelect() {
        return """
                select request.id, request.action_type, request.requester_admin_id,
                       request.target_type, request.target_id, request.request_payload_fingerprint,
                       request.safe_action_summary, request.safe_action_payload, request.status,
                       request.risk_level, request.required_approvals, request.policy_version,
                       request.expected_target_version, request.request_idempotency_key,
                       request.cancellation_idempotency_key, request.cancellation_request_hash,
                       request.execution_idempotency_key, request.executor_admin_id,
                       request.execution_reference, request.execution_failure_code,
                       request.execution_failure_summary, request.created_at, request.expires_at,
                       request.executed_at, request.correlation_id, request.version,
                       (select count(*) from admin_approval_decisions decision
                        where decision.approval_request_id = request.id and decision.decision = 'APPROVE') approval_count
                from admin_approval_requests request
                """;
    }

    private AssignmentRow assignment(ResultSet rs, int n) throws SQLException {
        return new AssignmentRow(rs.getString("id"), rs.getString("admin_user_id"), rs.getString("role_id"),
                rs.getString("status"), rs.getTimestamp("effective_at").toInstant(), instant(rs, "expires_at"),
                rs.getString("granted_by_admin_id"), rs.getString("reason"), instant(rs, "revoked_at"),
                rs.getString("revoked_by_admin_id"), rs.getString("revocation_reason"),
                rs.getString("request_hash"), rs.getString("revocation_idempotency_key"),
                rs.getString("revocation_request_hash"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ApprovalRow approval(ResultSet rs, int n) throws SQLException {
        return new ApprovalRow(rs.getString("id"), rs.getString("action_type"),
                rs.getString("requester_admin_id"), rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("request_payload_fingerprint"), rs.getString("safe_action_summary"),
                objectMap(rs.getString("safe_action_payload")), rs.getString("status"),
                rs.getString("risk_level"), rs.getInt("required_approvals"), rs.getInt("approval_count"),
                rs.getLong("policy_version"), nullableLong(rs, "expected_target_version"),
                rs.getString("request_idempotency_key"), rs.getString("cancellation_idempotency_key"),
                rs.getString("cancellation_request_hash"), rs.getString("execution_idempotency_key"),
                rs.getString("executor_admin_id"), rs.getString("execution_reference"),
                rs.getString("execution_failure_code"), rs.getString("execution_failure_summary"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                instant(rs, "executed_at"), rs.getString("correlation_id"), rs.getLong("version"));
    }

    private DecisionRow decision(ResultSet rs, int n) throws SQLException {
        return new DecisionRow(rs.getString("id"), rs.getString("approval_request_id"),
                rs.getString("approver_admin_id"), rs.getString("decision"), rs.getString("reason"),
                rs.getString("idempotency_key"), rs.getString("request_hash"),
                rs.getTimestamp("created_at").toInstant());
    }

    private EventRow mapEvent(ResultSet rs, int n) throws SQLException {
        return new EventRow(rs.getString("id"), rs.getString("event_type"), rs.getString("actor_admin_id"),
                rs.getString("subject_admin_id"), rs.getString("approval_request_id"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("reason"),
                rs.getString("outcome"), stringMap(rs.getString("safe_metadata")),
                rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private String escape(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Governance metadata could not be serialized."); }
    }

    private Map<String, Object> objectMap(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Governance payload could not be read."); }
    }

    private Map<String, String> stringMap(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Governance metadata could not be read."); }
    }

    record UserRow(String id, String displayName, String status, String accountType) { }
    record AdminRow(String id, String displayName, String accountState, boolean enabled,
                    boolean temporary, Instant lastChange) { }
    record RoleRow(String role, String description, String permission) { }
    record AssignmentRow(String id, String userId, String role, String status,
                         Instant effectiveAt, Instant expiresAt, String grantedBy,
                         String reason, Instant revokedAt, String revokedBy,
                         String revocationReason, String requestHash, String revocationIdempotencyKey,
                         String revocationRequestHash, long version, Instant createdAt) { }
    record AssignmentInsert(String id, String userId, String role, Instant effectiveAt,
                            Instant expiresAt, String actorId, String reason,
                            String idempotencyKey, String requestHash,
                            String correlationId, Instant now) { }
    record PolicyRow(String actionType, String riskLevel, String approvalMode,
                     int requiredApprovals, boolean requesterMayApprove,
                     int expiresMinutes, String requesterPermission,
                     String approverPermission, BigDecimal threshold,
                     String thresholdCurrency, boolean enabled, long version) { }
    record ApprovalRow(String id, String actionType, String requesterId,
                       String targetType, String targetId, String fingerprint,
                       String summary, Map<String, Object> payload, String status,
                       String riskLevel, int requiredApprovals, int approvalCount,
                       long policyVersion, Long expectedTargetVersion,
                       String requestIdempotencyKey, String cancellationIdempotencyKey,
                       String cancellationRequestHash, String executionIdempotencyKey,
                       String executorId, String executionReference,
                       String failureCode, String failureSummary,
                       Instant createdAt, Instant expiresAt, Instant executedAt,
                       String correlationId, long version) { }
    record ApprovalInsert(String id, String actionType, String requesterId,
                          String targetType, String targetId, String fingerprint,
                          String summary, Map<String, Object> payload, String riskLevel,
                          int requiredApprovals, long policyVersion,
                          Long expectedTargetVersion, String idempotencyKey,
                          String correlationId, Instant now, Instant expiresAt) { }
    record DecisionRow(String id, String approvalId, String actorId, String decision,
                       String reason, String idempotencyKey, String requestHash,
                       Instant createdAt) { }
    record DecisionInsert(String id, String approvalId, String actorId, String decision,
                          String reason, String idempotencyKey, String requestHash,
                          Instant now) { }
    record EventRow(String id, String eventType, String actorId, String subjectId,
                    String approvalId, String targetType, String targetId,
                    String reason, String outcome, Map<String, String> metadata,
                    String correlationId, Instant createdAt) { }
    record EventInsert(String id, String eventType, String actorId, String subjectId,
                       String approvalId, String targetType, String targetId,
                       String reason, String outcome, Map<String, String> metadata,
                       String correlationId, Instant now) { }
}
