package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.dto.AdminTimelineEntryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class ModerationCaseEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(ModerationCaseEventInsert event) {
        jdbcTemplate.update("""
                insert into moderation_case_events (
                    id, moderation_case_id, listing_id, event_type, actor_user_id,
                    previous_state, new_state, previous_assigned_admin_user_id,
                    new_assigned_admin_user_id, reason, correlation_id, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id(),
                event.moderationCaseId(),
                event.listingId(),
                event.eventType(),
                event.actorUserId(),
                event.previousState(),
                event.newState(),
                event.previousAssignedAdminUserId(),
                event.newAssignedAdminUserId(),
                event.reason(),
                event.correlationId(),
                Timestamp.from(event.occurredAt()));
    }

    public List<AdminTimelineEntryResponse> findTimeline(String caseId) {
        return jdbcTemplate.query("""
                select id, moderation_case_id, listing_id, event_type, actor_user_id,
                       previous_state, new_state, previous_assigned_admin_user_id,
                       new_assigned_admin_user_id, reason, correlation_id, created_at
                from moderation_case_events
                where moderation_case_id = ?
                order by created_at asc, id asc
                """,
                (rs, rowNum) -> {
                    Map<String, String> metadata = new LinkedHashMap<>();
                    putIfPresent(metadata, "previousAssignedAdminUserId",
                            rs.getString("previous_assigned_admin_user_id"));
                    putIfPresent(metadata, "newAssignedAdminUserId",
                            rs.getString("new_assigned_admin_user_id"));
                    String actorId = rs.getString("actor_user_id");
                    String eventType = rs.getString("event_type");
                    boolean adminAction = eventType.equals("CASE_CLAIMED")
                            || eventType.equals("CASE_RELEASED")
                            || eventType.equals("CASE_RESOLVED");
                    return new AdminTimelineEntryResponse(
                            rs.getString("id"),
                            rs.getTimestamp("created_at").toInstant(),
                            eventType,
                            adminAction ? "PLATFORM_ADMIN" : "MARKETPLACE_USER",
                            actorId,
                            actorId,
                            adminAction ? "HUMAN_ADMIN" : "SYSTEM",
                            "LISTING",
                            rs.getString("listing_id"),
                            rs.getString("moderation_case_id"),
                            rs.getString("previous_state"),
                            rs.getString("new_state"),
                            rs.getString("reason"),
                            rs.getString("correlation_id"),
                            Map.copyOf(metadata));
                },
                caseId);
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }
}
