package com.msb.ecom.product_service.enforcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Source;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
class EnforcementRepository {
    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() { };
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    Optional<Target> lockListing(String id) {
        return jdbcTemplate.query("select id, version, status, moderation_status from listings where id = ? for update",
                (rs, rowNum) -> new Target(rs.getString("id"), rs.getLong("version"),
                        rs.getString("status"), rs.getString("moderation_status")), id).stream().findFirst();
    }

    Optional<Target> listing(String id) {
        return jdbcTemplate.query("select id, version, status, moderation_status from listings where id = ?",
                (rs, rowNum) -> new Target(rs.getString("id"), rs.getLong("version"),
                        rs.getString("status"), rs.getString("moderation_status")), id).stream().findFirst();
    }

    Optional<Idempotency> idempotency(String type, String key, boolean lock) {
        if (key == null) return Optional.empty();
        return jdbcTemplate.query("""
                select command_fingerprint, enforcement_action_id from enforcement_command_idempotency
                where command_type = ? and idempotency_key = ?
                """ + (lock ? " for update" : ""),
                (rs, rowNum) -> new Idempotency(rs.getString(1), rs.getString(2)), type, key).stream().findFirst();
    }

    boolean reserve(String type, String key, String fingerprint, Instant now) {
        try {
            return jdbcTemplate.update("""
                    insert into enforcement_command_idempotency
                    (command_type, idempotency_key, command_fingerprint, created_at) values (?, ?, ?, ?)
                    """, type, key, fingerprint, Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException exception) { return false; }
    }

    void complete(String type, String key, String actionId, Instant now) {
        int updated = jdbcTemplate.update("""
                update enforcement_command_idempotency set enforcement_action_id = ?, completed_at = ?
                where command_type = ? and idempotency_key = ? and enforcement_action_id is null
                """, actionId, Timestamp.from(now), type, key);
        if (updated != 1) throw new EnforcementExceptions.Conflict("The enforcement command could not be completed.");
    }

    void insert(Action action) {
        jdbcTemplate.update("""
                insert into enforcement_actions (
                    id, target_type, target_id, action_type, version, effective_at, expires_at, reason_code,
                    reason, case_id, target_version_at_decision, source, created_by_actor_id,
                    created_by_actor_display_name, created_at, correlation_id, request_id,
                    idempotency_key, command_fingerprint, safe_metadata
                ) values (?, 'LISTING', ?, ?, 0, ?, ?, ?, ?, ?, ?, 'HUMAN_ADMIN', ?, ?, ?, ?, ?, ?, ?, cast(? as json))
                """, action.id(), action.targetId(), action.actionType().name(), Timestamp.from(action.effectiveAt()),
                timestamp(action.expiresAt()), action.reasonCode(), action.reason(), action.caseId(),
                action.targetVersion(), action.actorId(), action.actorDisplay(), Timestamp.from(action.createdAt()),
                action.correlationId(), action.requestId(), action.idempotencyKey(), action.fingerprint(),
                json(action.metadata()));
        action.scopes().forEach(scope -> jdbcTemplate.update(
                "insert into enforcement_action_scopes (enforcement_action_id, scope) values (?, ?)",
                action.id(), scope.name()));
    }

    void event(Event event) {
        jdbcTemplate.update("""
                insert into enforcement_events (
                    event_id, enforcement_action_id, event_type, previous_state, new_state, occurred_at,
                    actor_type, actor_id, actor_display_name, source, reason_code, reason,
                    correlation_id, request_id, safe_metadata
                ) values (?, ?, ?, ?, ?, ?, 'PLATFORM_ADMIN', ?, ?, 'HUMAN_ADMIN', ?, ?, ?, ?, cast(? as json))
                """, event.eventId(), event.actionId(), event.type(), event.previousState(), event.newState(),
                Timestamp.from(event.occurredAt()), event.actorId(), event.actorDisplay(), event.reasonCode(),
                event.reason(), event.correlationId(), event.requestId(), json(event.metadata()));
    }

    Optional<Action> action(String id, boolean lock) {
        List<Action> rows = jdbcTemplate.query("""
                select id, target_id, action_type, version, effective_at, expires_at, reason_code, reason,
                       case_id, target_version_at_decision, created_by_actor_id, created_by_actor_display_name,
                       created_at, revoked_at, correlation_id, request_id, idempotency_key,
                       command_fingerprint, safe_metadata
                from enforcement_actions where id = ?
                """ + (lock ? " for update" : ""), this::action, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(withScopes(rows.getFirst()));
    }

    List<Action> active(String targetId, Instant at) {
        return jdbcTemplate.query("""
                select id, target_id, action_type, version, effective_at, expires_at, reason_code, reason,
                       case_id, target_version_at_decision, created_by_actor_id, created_by_actor_display_name,
                       created_at, revoked_at, correlation_id, request_id, idempotency_key,
                       command_fingerprint, safe_metadata
                from enforcement_actions where target_type = 'LISTING' and target_id = ? and revoked_at is null
                  and effective_at <= ? and (expires_at is null or expires_at > ?)
                order by created_at, id
                """, this::action, targetId, Timestamp.from(at), Timestamp.from(at)).stream()
                .map(this::withScopes).toList();
    }

    // Loads all active actions and their scopes in two bounded queries for commerce batch decisions.
    List<Action> activeForTargets(Set<String> targetIds, Instant at) {
        if (targetIds == null || targetIds.isEmpty()) return List.of();
        String targetPlaceholders = String.join(",", targetIds.stream().map(ignored -> "?").toList());
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>(targetIds);
        parameters.add(Timestamp.from(at));
        parameters.add(Timestamp.from(at));
        List<Action> actions = jdbcTemplate.query("""
                select id, target_id, action_type, version, effective_at, expires_at, reason_code, reason,
                       case_id, target_version_at_decision, created_by_actor_id, created_by_actor_display_name,
                       created_at, revoked_at, correlation_id, request_id, idempotency_key,
                       command_fingerprint, safe_metadata
                from enforcement_actions where target_type = 'LISTING' and target_id in (%s)
                  and revoked_at is null and effective_at <= ? and (expires_at is null or expires_at > ?)
                order by target_id, created_at, id
                """.formatted(targetPlaceholders), this::action, parameters.toArray());
        if (actions.isEmpty()) return List.of();
        String actionPlaceholders = String.join(",", actions.stream().map(ignored -> "?").toList());
        Map<String, Set<Scope>> scopesByAction = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select enforcement_action_id, scope from enforcement_action_scopes
                where enforcement_action_id in (%s) order by enforcement_action_id, scope
                """.formatted(actionPlaceholders), (rs, row) -> new ScopeRow(
                rs.getString("enforcement_action_id"), Scope.valueOf(rs.getString("scope"))),
                actions.stream().map(Action::id).toArray()).forEach(row -> scopesByAction
                .computeIfAbsent(row.actionId(), ignored -> new LinkedHashSet<>()).add(row.scope()));
        return actions.stream().map(action -> action.withScopes(
                scopesByAction.getOrDefault(action.id(), Set.of()))).toList();
    }

    List<Action> all(String targetId) {
        return jdbcTemplate.query("""
                select id, target_id, action_type, version, effective_at, expires_at, reason_code, reason,
                       case_id, target_version_at_decision, created_by_actor_id, created_by_actor_display_name,
                       created_at, revoked_at, correlation_id, request_id, idempotency_key,
                       command_fingerprint, safe_metadata
                from enforcement_actions where target_type = 'LISTING' and target_id = ?
                order by created_at desc, id desc
                """, this::action, targetId).stream().map(this::withScopes).toList();
    }

    Set<String> restrictedTargets(Set<String> targetIds, Scope scope, Instant at) {
        if (targetIds == null || targetIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", targetIds.stream().map(ignored -> "?").toList());
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>();
        parameters.add(scope.name());
        parameters.add(Timestamp.from(at));
        parameters.add(Timestamp.from(at));
        parameters.addAll(targetIds);
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                select distinct a.target_id
                from enforcement_actions a
                join enforcement_action_scopes s on s.enforcement_action_id = a.id
                where a.target_type = 'LISTING' and s.scope = ? and a.revoked_at is null
                  and a.effective_at <= ? and (a.expires_at is null or a.expires_at > ?)
                  and a.target_id in (%s)
                """.formatted(placeholders), String.class, parameters.toArray()));
    }

    void revoke(Action action, EnforcementPolicy.NormalizedRevoke command, Actor actor,
                String fingerprint, Instant now) {
        int updated = jdbcTemplate.update("""
                update enforcement_actions set version = version + 1, revoked_at = ?, revoked_by_actor_id = ?,
                    revoked_by_actor_display_name = ?, revoke_reason_code = ?, revoke_reason = ?,
                    revoke_idempotency_key = ?, revoke_command_fingerprint = ?
                where id = ? and version = ? and revoked_at is null
                """, Timestamp.from(now), actor.id(), actor.display(), command.reasonCode(), command.reason(),
                command.idempotencyKey(), fingerprint, action.id(), command.expectedEnforcementVersion());
        if (updated != 1) throw new EnforcementExceptions.Conflict("The enforcement action version is stale.");
    }

    Set<Scope> scopes(String actionId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "select scope from enforcement_action_scopes where enforcement_action_id = ? order by scope",
                String.class, actionId).stream().map(Scope::valueOf).toList());
    }

    List<Timeline> timeline(String targetId) {
        return jdbcTemplate.query("""
                select e.event_id, e.event_type, e.previous_state, e.new_state, e.occurred_at,
                       e.actor_id, e.actor_display_name, e.reason_code, e.reason, e.correlation_id,
                       e.request_id, e.safe_metadata, a.id action_id, a.action_type, a.case_id
                from enforcement_events e join enforcement_actions a on a.id = e.enforcement_action_id
                where a.target_type = 'LISTING' and a.target_id = ? order by e.occurred_at, e.event_id
                """, (rs, rowNum) -> new Timeline(rs.getString("event_id"), rs.getString("event_type"),
                rs.getString("previous_state"), rs.getString("new_state"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_id"), rs.getString("actor_display_name"), rs.getString("reason_code"),
                rs.getString("reason"), rs.getString("correlation_id"), rs.getString("request_id"),
                map(rs.getString("safe_metadata")), rs.getString("action_id"),
                ActionType.valueOf(rs.getString("action_type")), rs.getString("case_id")), targetId);
    }

    private Action action(ResultSet rs, int row) throws SQLException {
        return new Action(rs.getString("id"), rs.getString("target_id"),
                ActionType.valueOf(rs.getString("action_type")), Set.of(), rs.getLong("version"),
                rs.getTimestamp("effective_at").toInstant(), instant(rs, "expires_at"), rs.getString("reason_code"),
                rs.getString("reason"), rs.getString("case_id"), rs.getLong("target_version_at_decision"),
                rs.getString("created_by_actor_id"), rs.getString("created_by_actor_display_name"),
                rs.getTimestamp("created_at").toInstant(), instant(rs, "revoked_at"), rs.getString("correlation_id"),
                rs.getString("request_id"), rs.getString("idempotency_key"), rs.getString("command_fingerprint"),
                map(rs.getString("safe_metadata")));
    }

    private Action withScopes(Action action) { return action.withScopes(scopes(action.id())); }
    private String json(Map<String, String> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new EnforcementExceptions.Validation("Invalid metadata."); }
    }
    private Map<String, String> map(String value) {
        try { return value == null ? Map.of() : objectMapper.readValue(value, METADATA); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    record Target(String id, long version, String status, String moderationStatus) { }
    record Idempotency(String fingerprint, String actionId) { }
    record Actor(String id, String display) { }
    record Action(String id, String targetId, ActionType actionType, Set<Scope> scopes, long version,
            Instant effectiveAt, Instant expiresAt, String reasonCode, String reason, String caseId,
            long targetVersion, String actorId, String actorDisplay, Instant createdAt, Instant revokedAt,
            String correlationId, String requestId, String idempotencyKey, String fingerprint,
            Map<String, String> metadata) {
        Action withScopes(Set<Scope> values) { return new Action(id, targetId, actionType, Set.copyOf(values), version,
                effectiveAt, expiresAt, reasonCode, reason, caseId, targetVersion, actorId, actorDisplay, createdAt,
                revokedAt, correlationId, requestId, idempotencyKey, fingerprint, metadata); }
    }
    record Event(String eventId, String actionId, String type, String previousState, String newState,
            Instant occurredAt, String actorId, String actorDisplay, String reasonCode, String reason,
            String correlationId, String requestId, Map<String, String> metadata) { }
    record Timeline(String eventId, String type, String previousState, String newState, Instant occurredAt,
            String actorId, String actorDisplay, String reasonCode, String reason, String correlationId,
            String requestId, Map<String, String> metadata, String actionId, ActionType actionType, String caseId) { }
    private record ScopeRow(String actionId, Scope scope) { }
}
