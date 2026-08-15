package com.msb.ecom.auth_service.repository;

import com.msb.ecom.auth_service.dto.AdminTimelineEntryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class BusinessApplicationTimelineRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(
            String id,
            String providerEventId,
            String applicationId,
            String persistenceSource,
            String eventType,
            String outcome,
            String previousState,
            String newState,
            String reason,
            String payloadHash,
            String actorUserId,
            String correlationId,
            Instant occurredAt) {
        jdbcTemplate.update("""
                insert into business_verification_events (
                    id, provider_event_id, application_id, source, event_type, outcome,
                    previous_state, new_state, reason, payload_hash, actor_user_id,
                    correlation_id, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                providerEventId,
                applicationId,
                persistenceSource,
                eventType,
                outcome,
                previousState,
                newState,
                reason,
                payloadHash,
                actorUserId,
                correlationId,
                Timestamp.from(occurredAt));
    }

    public List<AdminTimelineEntryResponse> findByApplicationId(String applicationId) {
        return jdbcTemplate.query("""
                select e.id, e.provider_event_id, e.event_type, e.outcome,
                       e.previous_state, e.new_state, e.reason, e.actor_user_id,
                       e.source, e.correlation_id, e.created_at,
                       coalesce(nullif(trim(u.display_name), ''), u.public_handle) as actor_display
                from business_verification_events e
                left join users u on u.id = e.actor_user_id
                where e.application_id = ?
                order by e.created_at asc, e.id asc
                """,
                (rs, rowNum) -> {
                    String persistenceSource = rs.getString("source");
                    String actorId = rs.getString("actor_user_id");
                    Map<String, String> metadata = new LinkedHashMap<>();
                    putIfPresent(metadata, "outcome", rs.getString("outcome"));
                    putIfPresent(metadata, "providerEventId", rs.getString("provider_event_id"));
                    return new AdminTimelineEntryResponse(
                            rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getString("event_type"),
                            actorType(persistenceSource, actorId),
                            actorId,
                            actorDisplay(persistenceSource, actorId, rs.getString("actor_display")),
                            "ADMIN".equals(persistenceSource) ? "HUMAN_ADMIN" : "SYSTEM",
                            "BUSINESS_APPLICATION",
                            applicationId,
                            null,
                            rs.getString("previous_state"),
                            rs.getString("new_state"),
                            rs.getString("reason"),
                            rs.getString("correlation_id"),
                            Map.copyOf(metadata));
                },
                applicationId);
    }

    private static String actorType(String source, String actorId) {
        if ("ADMIN".equals(source)) {
            return "PLATFORM_ADMIN";
        }
        if ("APPLICANT".equals(source)) {
            return "MARKETPLACE_USER";
        }
        return actorId == null ? "SYSTEM" : "MARKETPLACE_USER";
    }

    private static String actorDisplay(String source, String actorId, String display) {
        if (display != null && !display.isBlank()) {
            return display;
        }
        if (actorId != null && !actorId.isBlank()) {
            return actorId;
        }
        return "PROVIDER".equals(source) ? "Verification provider" : "System";
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
