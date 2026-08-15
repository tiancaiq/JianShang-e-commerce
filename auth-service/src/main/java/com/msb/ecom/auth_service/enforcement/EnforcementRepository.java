package com.msb.ecom.auth_service.enforcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Source;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
class EnforcementRepository {

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    Optional<TargetSnapshot> lockBusiness(String id) {
        return jdbcTemplate.query("select id, version from businesses where id = ? for update",
                (rs, rowNum) -> new TargetSnapshot(rs.getString("id"), rs.getLong("version")), id)
                .stream().findFirst();
    }

    Optional<Idempotency> findIdempotency(String commandType, String key, boolean lock) {
        if (key == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                select command_fingerprint, enforcement_action_id
                from enforcement_command_idempotency
                where command_type = ? and idempotency_key = ?
                """ + (lock ? " for update" : ""),
                (rs, rowNum) -> new Idempotency(rs.getString("command_fingerprint"),
                        rs.getString("enforcement_action_id")), commandType, key).stream().findFirst();
    }

    boolean reserveIdempotency(String commandType, String key, String fingerprint, Instant now) {
        try {
            return jdbcTemplate.update("""
                    insert into enforcement_command_idempotency (
                        command_type, idempotency_key, command_fingerprint, created_at
                    ) values (?, ?, ?, ?)
                    """, commandType, key, fingerprint, Timestamp.from(now)) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    void completeIdempotency(String commandType, String key, String actionId, Instant now) {
        int updated = jdbcTemplate.update("""
                update enforcement_command_idempotency
                set enforcement_action_id = ?, completed_at = ?
                where command_type = ? and idempotency_key = ? and enforcement_action_id is null
                """, actionId, Timestamp.from(now), commandType, key);
        if (updated != 1) {
            throw new EnforcementExceptions.Conflict("The enforcement command could not be completed.");
        }
    }

    void insertAction(Action action) {
        jdbcTemplate.update("""
                insert into enforcement_actions (
                    id, target_type, target_id, action_type, version, effective_at, expires_at,
                    reason_code, reason, case_id, parent_enforcement_action_id,
                    target_version_at_decision, source, created_by_actor_id,
                    created_by_actor_display_name, created_at, correlation_id, request_id,
                    idempotency_key, command_fingerprint, safe_metadata
                ) values (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json))
                """,
                action.id(), action.targetType().name(), action.targetId(), action.actionType().name(),
                Timestamp.from(action.effectiveAt()), nullableTimestamp(action.expiresAt()),
                action.reasonCode(), action.reason(), action.caseId(), action.targetVersionAtDecision(),
                action.source().name(), action.createdByActorId(), action.createdByActorDisplayName(),
                Timestamp.from(action.createdAt()), action.correlationId(), action.requestId(),
                action.idempotencyKey(), action.commandFingerprint(), json(action.safeMetadata()));
        for (Scope scope : action.scopes()) {
            jdbcTemplate.update("insert into enforcement_action_scopes (enforcement_action_id, scope) values (?, ?)",
                    action.id(), scope.name());
        }
    }

    void insertEvent(Event event) {
        jdbcTemplate.update("""
                insert into enforcement_events (
                    event_id, enforcement_action_id, event_type, previous_state, new_state,
                    occurred_at, actor_type, actor_id, actor_display_name, source,
                    reason_code, reason, correlation_id, request_id, safe_metadata
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json))
                """, event.eventId(), event.enforcementActionId(), event.eventType(), event.previousState(),
                event.newState(), Timestamp.from(event.occurredAt()), event.actorType(), event.actorId(),
                event.actorDisplayName(), event.source().name(), event.reasonCode(), event.reason(),
                event.correlationId(), event.requestId(), json(event.safeMetadata()));
    }

    Optional<Action> findAction(String actionId, boolean lock) {
        List<Action> actions = jdbcTemplate.query("""
                select id, target_type, target_id, action_type, version, effective_at, expires_at,
                       reason_code, reason, case_id, target_version_at_decision, source,
                       created_by_actor_id, created_by_actor_display_name, created_at, revoked_at,
                       correlation_id, request_id, idempotency_key, command_fingerprint,
                       revoke_idempotency_key, revoke_command_fingerprint, safe_metadata
                from enforcement_actions where id = ?
                """ + (lock ? " for update" : ""), this::action, actionId);
        if (actions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withScopes(actions.getFirst()));
    }

    List<Action> activeActions(TargetType targetType, String targetId, Instant now) {
        List<Action> actions = jdbcTemplate.query("""
                select id, target_type, target_id, action_type, version, effective_at, expires_at,
                       reason_code, reason, case_id, target_version_at_decision, source,
                       created_by_actor_id, created_by_actor_display_name, created_at, revoked_at,
                       correlation_id, request_id, idempotency_key, command_fingerprint,
                       revoke_idempotency_key, revoke_command_fingerprint, safe_metadata
                from enforcement_actions
                where target_type = ? and target_id = ? and revoked_at is null
                  and effective_at <= ? and (expires_at is null or expires_at > ?)
                order by created_at, id
                """, this::action, targetType.name(), targetId, Timestamp.from(now), Timestamp.from(now));
        return actions.stream().map(this::withScopes).toList();
    }

    List<Action> actions(TargetType targetType, String targetId) {
        return jdbcTemplate.query("""
                select id, target_type, target_id, action_type, version, effective_at, expires_at,
                       reason_code, reason, case_id, target_version_at_decision, source,
                       created_by_actor_id, created_by_actor_display_name, created_at, revoked_at,
                       correlation_id, request_id, idempotency_key, command_fingerprint,
                       revoke_idempotency_key, revoke_command_fingerprint, safe_metadata
                from enforcement_actions
                where target_type = ? and target_id = ?
                order by created_at desc, id desc
                """, this::action, targetType.name(), targetId).stream().map(this::withScopes).toList();
    }

    void revoke(Action action, EnforcementPolicy.NormalizedRevoke command, Actor actor,
                String fingerprint, Instant now) {
        int updated = jdbcTemplate.update("""
                update enforcement_actions
                set version = version + 1, revoked_at = ?, revoked_by_actor_id = ?,
                    revoked_by_actor_display_name = ?, revoke_reason_code = ?, revoke_reason = ?,
                    revoke_idempotency_key = ?, revoke_command_fingerprint = ?
                where id = ? and version = ? and revoked_at is null
                """, Timestamp.from(now), actor.id(), actor.displayName(), command.reasonCode(), command.reason(),
                command.idempotencyKey(), fingerprint, action.id(), command.expectedEnforcementVersion());
        if (updated != 1) {
            throw new EnforcementExceptions.Conflict("The enforcement action version is stale or already revoked.");
        }
    }

    List<TimelineRow> timeline(TargetType targetType, String targetId) {
        return jdbcTemplate.query("""
                select e.event_id, e.event_type, e.previous_state, e.new_state, e.occurred_at,
                       e.actor_type, e.actor_id, e.actor_display_name, e.source, e.reason_code,
                       e.reason, e.correlation_id, e.request_id, e.safe_metadata,
                       a.id action_id, a.target_type, a.target_id, a.action_type, a.case_id
                from enforcement_events e
                join enforcement_actions a on a.id = e.enforcement_action_id
                where a.target_type = ? and a.target_id = ?
                order by e.occurred_at, e.event_id
                """, (rs, rowNum) -> new TimelineRow(
                rs.getString("event_id"), rs.getString("event_type"), rs.getString("previous_state"),
                rs.getString("new_state"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("actor_display_name"),
                Source.valueOf(rs.getString("source")), rs.getString("reason_code"), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("request_id"), metadata(rs.getString("safe_metadata")),
                rs.getString("action_id"), TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"), ActionType.valueOf(rs.getString("action_type")),
                rs.getString("case_id")), targetType.name(), targetId);
    }

    Set<Scope> scopes(String actionId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "select scope from enforcement_action_scopes where enforcement_action_id = ? order by scope",
                String.class, actionId).stream().map(Scope::valueOf).toList());
    }

    private Action action(ResultSet rs, int rowNum) throws SQLException {
        return new Action(rs.getString("id"), TargetType.valueOf(rs.getString("target_type")),
                rs.getString("target_id"), ActionType.valueOf(rs.getString("action_type")), Set.of(),
                rs.getLong("version"), rs.getTimestamp("effective_at").toInstant(),
                nullableInstant(rs, "expires_at"), rs.getString("reason_code"), rs.getString("reason"),
                rs.getString("case_id"), rs.getLong("target_version_at_decision"),
                Source.valueOf(rs.getString("source")), rs.getString("created_by_actor_id"),
                rs.getString("created_by_actor_display_name"), rs.getTimestamp("created_at").toInstant(),
                nullableInstant(rs, "revoked_at"), rs.getString("correlation_id"), rs.getString("request_id"),
                rs.getString("idempotency_key"), rs.getString("command_fingerprint"),
                rs.getString("revoke_idempotency_key"), rs.getString("revoke_command_fingerprint"),
                metadata(rs.getString("safe_metadata")));
    }

    private Action withScopes(Action action) {
        return action.withScopes(scopes(action.id()));
    }

    private String json(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new EnforcementExceptions.Validation("Safe metadata could not be encoded.");
        }
    }

    private Map<String, String> metadata(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, METADATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored enforcement metadata is invalid", exception);
        }
    }

    private static Timestamp nullableTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    record TargetSnapshot(String id, long version) { }
    record Idempotency(String fingerprint, String actionId) { }
    record Actor(String id, String displayName) { }

    record Action(
            String id, TargetType targetType, String targetId, ActionType actionType, Set<Scope> scopes,
            long version, Instant effectiveAt, Instant expiresAt, String reasonCode, String reason,
            String caseId, long targetVersionAtDecision, Source source, String createdByActorId,
            String createdByActorDisplayName, Instant createdAt, Instant revokedAt, String correlationId,
            String requestId, String idempotencyKey, String commandFingerprint,
            String revokeIdempotencyKey, String revokeCommandFingerprint, Map<String, String> safeMetadata) {
        Action withScopes(Set<Scope> values) {
            return new Action(id, targetType, targetId, actionType, Set.copyOf(values), version, effectiveAt,
                    expiresAt, reasonCode, reason, caseId, targetVersionAtDecision, source, createdByActorId,
                    createdByActorDisplayName, createdAt, revokedAt, correlationId, requestId, idempotencyKey,
                    commandFingerprint, revokeIdempotencyKey, revokeCommandFingerprint, safeMetadata);
        }
    }

    record Event(
            String eventId, String enforcementActionId, String eventType, String previousState, String newState,
            Instant occurredAt, String actorType, String actorId, String actorDisplayName, Source source,
            String reasonCode, String reason, String correlationId, String requestId,
            Map<String, String> safeMetadata) { }

    record TimelineRow(
            String eventId, String eventType, String previousState, String newState, Instant occurredAt,
            String actorType, String actorId, String actorDisplayName, Source source, String reasonCode,
            String reason, String correlationId, String requestId, Map<String, String> safeMetadata,
            String actionId, TargetType targetType, String targetId, ActionType actionType, String caseId) { }
}
