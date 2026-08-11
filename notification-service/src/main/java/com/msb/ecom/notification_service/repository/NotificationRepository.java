package com.msb.ecom.notification_service.repository;

import com.msb.ecom.notification_service.model.NotificationSourceEvent;
import com.msb.ecom.notification_service.model.NotificationSourceRecord;
import com.msb.ecom.notification_service.model.NotificationReadRow;
import com.msb.ecom.notification_service.model.OrderConfirmedNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<NotificationSourceRecord> findSource(String consumerName, String eventId) {
        return source("""
                SELECT source_payload_hash AS payload_hash, state, outcome, safe_error_code
                FROM notification_source_events
                WHERE consumer_name = ? AND source_event_id = ?
                """, consumerName, eventId);
    }

    // Reads one stable recipient-owned page without selecting source or consumer internals.
    public List<NotificationReadRow> findRecipientPage(
            String recipientUserId,
            Instant beforeCreatedAt,
            String beforeNotificationId,
            int limit) {
        if (beforeCreatedAt == null) {
            return jdbc.query("""
                            SELECT id, type, message_key, message_args_json, route,
                                   read_at, created_at
                            FROM notifications
                            WHERE recipient_user_id = ?
                            ORDER BY created_at DESC, id DESC
                            LIMIT ?
                            """,
                    NotificationRepository::readRow,
                    recipientUserId,
                    limit);
        }
        return jdbc.query("""
                        SELECT id, type, message_key, message_args_json, route,
                               read_at, created_at
                        FROM notifications
                        WHERE recipient_user_id = ?
                          AND (created_at < ? OR (created_at = ? AND id < ?))
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """,
                NotificationRepository::readRow,
                recipientUserId,
                Timestamp.from(beforeCreatedAt),
                Timestamp.from(beforeCreatedAt),
                beforeNotificationId,
                limit);
    }

    public List<NotificationReadRow> findScopePage(
            String scopeType, String scopeId, Instant beforeCreatedAt,
            String beforeNotificationId, int limit) {
        if (beforeCreatedAt == null) {
            return jdbc.query("""
                            SELECT id, type, message_key, message_args_json, route,
                                   read_at, created_at
                            FROM notifications
                            WHERE recipient_scope_type = ? AND recipient_scope_id = ?
                            ORDER BY created_at DESC, id DESC LIMIT ?
                            """, NotificationRepository::readRow, scopeType, scopeId, limit);
        }
        return jdbc.query("""
                        SELECT id, type, message_key, message_args_json, route,
                               read_at, created_at
                        FROM notifications
                        WHERE recipient_scope_type = ? AND recipient_scope_id = ?
                          AND (created_at < ? OR (created_at = ? AND id < ?))
                        ORDER BY created_at DESC, id DESC LIMIT ?
                        """, NotificationRepository::readRow, scopeType, scopeId,
                Timestamp.from(beforeCreatedAt), Timestamp.from(beforeCreatedAt),
                beforeNotificationId, limit);
    }

    public int unreadCount(String scopeType, String scopeId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE recipient_scope_type = ? AND recipient_scope_id = ? AND read_at IS NULL
                """, Integer.class, scopeType, scopeId);
        return count == null ? 0 : count;
    }

    public boolean markScopeRead(
            String scopeType, String scopeId, String notificationId, Instant now) {
        int changed = jdbc.update("""
                        UPDATE notifications SET read_at = ?, updated_at = ?
                        WHERE id = ? AND recipient_scope_type = ? AND recipient_scope_id = ?
                          AND read_at IS NULL
                        """, Timestamp.from(now), Timestamp.from(now), notificationId,
                scopeType, scopeId);
        if (changed == 1) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM notifications
                    WHERE id = ? AND recipient_scope_type = ? AND recipient_scope_id = ?)
                """, Boolean.class, notificationId, scopeType, scopeId));
    }

    public void markScopeAllRead(String scopeType, String scopeId, Instant now) {
        jdbc.update("""
                UPDATE notifications SET read_at = ?, updated_at = ?
                WHERE recipient_scope_type = ? AND recipient_scope_id = ? AND read_at IS NULL
                """, Timestamp.from(now), Timestamp.from(now), scopeType, scopeId);
    }

    // Sets the first read timestamp only when the notification belongs to the resolved actor.
    public boolean markOwnedRead(String recipientUserId, String notificationId, Instant now) {
        int changed = jdbc.update("""
                        UPDATE notifications
                        SET read_at = ?, updated_at = ?
                        WHERE id = ? AND recipient_user_id = ? AND read_at IS NULL
                        """,
                Timestamp.from(now),
                Timestamp.from(now),
                notificationId,
                recipientUserId);
        if (changed == 1) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(
                            SELECT 1 FROM notifications
                            WHERE id = ? AND recipient_user_id = ?
                        )
                        """,
                Boolean.class,
                notificationId,
                recipientUserId));
    }

    // Marks only the resolved recipient's unread rows and intentionally returns no count.
    public void markAllOwnedRead(String recipientUserId, Instant now) {
        jdbc.update("""
                        UPDATE notifications
                        SET read_at = ?, updated_at = ?
                        WHERE recipient_user_id = ? AND read_at IS NULL
                        """,
                Timestamp.from(now),
                Timestamp.from(now),
                recipientUserId);
    }

    public Optional<NotificationSourceRecord> lockSource(String consumerName, String eventId) {
        return source("""
                SELECT source_payload_hash AS payload_hash, state, outcome, safe_error_code
                FROM notification_source_events
                WHERE consumer_name = ? AND source_event_id = ?
                FOR UPDATE
                """, consumerName, eventId);
    }

    // Begins source-event processing inside the same transaction as the notification projection.
    public void insertProcessing(
            String consumerName,
            NotificationSourceEvent event,
            String payloadHash,
            Instant retentionUntil,
            Instant now) {
        jdbc.update("""
                        INSERT INTO notification_source_events (
                            consumer_name, source_event_id, source_event_type,
                            source_event_version, source_payload_hash, state, outcome,
                            safe_error_code, source_occurred_at, correlation_id,
                            attempt_count, processed_at, retention_until, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'PROCESSING', NULL, NULL, ?, ?,
                                  1, NULL, ?, ?, ?)
                        """,
                consumerName,
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                payloadHash,
                Timestamp.from(event.occurredAt()),
                event.correlationId(),
                Timestamp.from(retentionUntil),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    // Stores only the allowlisted in-app projection, never the source envelope or payment fields.
    public void insertOrderConfirmed(
            String notificationId,
            String consumerName,
            NotificationSourceEvent event,
            OrderConfirmedNotification notification,
            String payloadHash,
            Instant retentionUntil,
            Instant now) {
        jdbc.update("""
                        INSERT INTO notifications (
                            id, recipient_user_id, type, message_key, message_args_json,
                            route, source_consumer_name, source_event_id, source_event_type,
                            source_event_version, source_occurred_at, source_payload_hash,
                            read_at, version, retention_until, created_at, updated_at
                        ) VALUES (?, ?, 'ORDER_CONFIRMED', 'ORDER_CONFIRMED_V1',
                                  JSON_OBJECT('orderId', ?), '/account', ?, ?, ?, ?, ?, ?,
                                  NULL, 0, ?, ?, ?)
                        """,
                notificationId,
                notification.recipientUserId(),
                notification.orderId(),
                consumerName,
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                Timestamp.from(event.occurredAt()),
                payloadHash,
                Timestamp.from(retentionUntil),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    // Persists one strictly validated commerce projection for a user or business audience.
    public void insertCommerceNotification(
            String notificationId, String consumerName, NotificationSourceEvent event,
            String scopeType, String scopeId, String type, String messageKey,
            String orderId, String businessId, String businessOrderId,
            String storeDisplayName, String route, String payloadHash,
            Instant retentionUntil, Instant now) {
        String args = storeDisplayName == null
                ? (businessOrderId == null
                    ? "{\"orderId\":\"" + orderId + "\"}"
                    : "{\"orderId\":\"" + orderId + "\",\"businessOrderId\":\"" + businessOrderId + "\"}")
                : "{\"orderId\":\"" + orderId + "\",\"businessOrderId\":\""
                    + businessOrderId + "\",\"storeDisplayName\":\""
                    + storeDisplayName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        jdbc.update("""
                        INSERT INTO notifications (
                            id, recipient_scope_type, recipient_scope_id, recipient_user_id,
                            business_id, business_order_id, type, message_key, message_args_json,
                            route, source_consumer_name, source_event_id, source_event_type,
                            source_event_version, source_occurred_at, source_payload_hash,
                            read_at, version, retention_until, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?, ?,
                                  NULL, 0, ?, ?, ?)
                        """, notificationId, scopeType, scopeId,
                "USER".equals(scopeType) ? scopeId : null,
                "BUSINESS".equals(scopeType) ? scopeId : null,
                businessOrderId, type, messageKey, args, route, consumerName,
                event.eventId(), event.eventType(), event.eventVersion(),
                Timestamp.from(event.occurredAt()), payloadHash,
                Timestamp.from(retentionUntil), Timestamp.from(now), Timestamp.from(now));
    }

    public void complete(
            String consumerName,
            String eventId,
            String outcome,
            String safeErrorCode,
            Instant now) {
        int changed = jdbc.update("""
                        UPDATE notification_source_events
                        SET state = CASE WHEN ? = 'CREATED' THEN 'COMPLETED' ELSE 'REJECTED' END,
                            outcome = ?,
                            safe_error_code = ?,
                            processed_at = ?,
                            updated_at = ?
                        WHERE consumer_name = ? AND source_event_id = ? AND state = 'PROCESSING'
                        """,
                outcome,
                outcome,
                safeErrorCode,
                Timestamp.from(now),
                Timestamp.from(now),
                consumerName,
                eventId);
        if (changed != 1) {
            throw new IllegalStateException("Notification source event completion was lost.");
        }
    }

    private Optional<NotificationSourceRecord> source(
            String sql,
            String consumerName,
            String eventId) {
        return jdbc.query(
                sql,
                (rs, rowNum) -> new NotificationSourceRecord(
                        rs.getString("payload_hash"),
                        rs.getString("state"),
                        rs.getString("outcome"),
                        rs.getString("safe_error_code")),
                consumerName,
                eventId).stream().findFirst();
    }

    private static NotificationReadRow readRow(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Timestamp readAt = rs.getTimestamp("read_at");
        return new NotificationReadRow(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("message_key"),
                rs.getString("message_args_json"),
                rs.getString("route"),
                readAt == null ? null : readAt.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
