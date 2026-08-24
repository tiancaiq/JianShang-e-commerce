package com.msb.ecom.auth_service.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SystemOperationsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public Optional<CommandRow> command(String actor, String type, String key, boolean lock) {
        return jdbc.query("""
                select id, request_hash, owner_service, target_type, target_id, reason, state, result,
                       correlation_id, created_at
                from system_operation_commands
                where actor_user_id = ? and command_type = ? and idempotency_key = ?
                """ + (lock ? " for update" : ""), (rs, rowNum) -> new CommandRow(
                        rs.getString("id"), rs.getString("request_hash"),
                        rs.getString("owner_service"), rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("reason"), rs.getString("state"), rs.getString("result"),
                        rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant()),
                actor, type, key).stream().findFirst();
    }

    public boolean insertCommand(CommandInsert value) {
        try {
            jdbc.update("""
                    insert into system_operation_commands (
                        id, actor_user_id, command_type, owner_service, target_type, target_id,
                        idempotency_key, request_hash, reason, state, result,
                        correlation_id, created_at, completed_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', null, ?, ?, null)
                    """, value.id(), value.actor(), value.commandType(), value.ownerService(),
                    value.targetType(), value.targetId(), value.idempotencyKey(), value.requestHash(), value.reason(),
                    value.correlationId(), Timestamp.from(value.createdAt()));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public void complete(String commandId, String state, String result, Instant now) {
        jdbc.update("""
                update system_operation_commands
                set state = ?, result = ?, completed_at = ?
                where id = ? and state = 'IN_PROGRESS'
                """, state, result, Timestamp.from(now), commandId);
    }

    public void event(EventInsert value) {
        jdbc.update("""
                insert into system_operation_events (
                    event_id, command_id, event_type, actor_user_id, target_type,
                    target_id, reason, outcome, correlation_id, request_id,
                    safe_metadata, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json), ?)
                """, value.eventId(), value.commandId(), value.eventType(), value.actor(),
                value.targetType(), value.targetId(), value.reason(), value.outcome(),
                value.correlationId(), value.requestId(), json(value.metadata()),
                Timestamp.from(value.createdAt()));
    }

    public List<SystemContracts.OperationEvent> recent(int limit) {
        return jdbc.query("""
                select e.command_id, e.event_type, e.actor_user_id, e.target_type,
                       e.target_id, e.reason, e.outcome, e.correlation_id, e.created_at,
                       c.command_type
                from system_operation_events e
                join system_operation_commands c on c.id = e.command_id
                order by e.created_at desc, e.event_id desc
                limit ?
                """, (rs, rowNum) -> new SystemContracts.OperationEvent(
                        rs.getString("command_id"), rs.getString("event_type"),
                        rs.getString("actor_user_id"), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("reason"),
                        rs.getString("outcome"), rs.getString("correlation_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        Map.of("commandType", rs.getString("command_type"))), limit);
    }

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Operational audit metadata could not be serialized.");
        }
    }

    public record CommandRow(String id, String requestHash, String ownerService, String targetType, String targetId,
            String reason, String state, String result, String correlationId, Instant createdAt) { }

    public record CommandInsert(String id, String actor, String commandType, String ownerService, String targetType,
            String targetId, String idempotencyKey, String requestHash, String reason,
            String correlationId, Instant createdAt) { }

    public record EventInsert(String eventId, String commandId, String eventType, String actor,
            String targetType, String targetId, String reason, String outcome,
            String correlationId, String requestId, Map<String, String> metadata,
            Instant createdAt) { }
}
