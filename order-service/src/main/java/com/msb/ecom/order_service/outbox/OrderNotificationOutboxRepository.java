package com.msb.ecom.order_service.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class OrderNotificationOutboxRepository {
    private final JdbcTemplate jdbc;

    public OrderNotificationOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<OutboxRecord> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT id, aggregate_type, aggregate_id, event_type, event_version,
                       correlation_id, causation_id, created_at
                FROM order_outbox_events
                WHERE notification_published_at IS NULL
                  AND notification_next_attempt_at <= ?
                  AND ((event_type = 'order.confirmed' AND event_version = 2)
                    OR event_type IN ('business_order.accepted',
                        'business_order.processing_started', 'business_order.shipped',
                        'business_order.delivered_demo', 'order.cancellation_completed',
                        'return.requested','return.authorized','return.received','return.refund_completed'))
                ORDER BY created_at, id LIMIT ?
                """, (rs, rowNum) -> new OutboxRecord(
                    rs.getString("id"), rs.getString("aggregate_type"),
                    rs.getString("aggregate_id"), rs.getString("event_type"),
                    rs.getInt("event_version"), rs.getString("correlation_id"),
                    rs.getString("causation_id"), rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(now), limit);
    }

    public OrderAudience audience(OutboxRecord event) {
        String orderId = "BUSINESS_ORDER".equals(event.aggregateType())
                ? jdbc.queryForObject("SELECT order_id FROM business_orders WHERE id = ?",
                    String.class, event.aggregateId())
                : "RETURN".equals(event.aggregateType())
                    ? jdbc.queryForObject("SELECT order_id FROM business_order_returns WHERE id = ?",
                        String.class, event.aggregateId()) : event.aggregateId();
        if (orderId == null) throw new IllegalStateException("Order audience is unavailable.");
        String buyerId = jdbc.queryForObject("SELECT buyer_id FROM orders WHERE id = ?",
                String.class, orderId);
        List<BusinessAudience> businesses = jdbc.query("""
                SELECT id, business_id, store_name FROM business_orders
                WHERE order_id = ? ORDER BY id
                """, (rs, rowNum) -> new BusinessAudience(
                    rs.getString("id"), rs.getString("business_id"), rs.getString("store_name")),
                orderId);
        if (buyerId == null || businesses.isEmpty()) {
            throw new IllegalStateException("Order audience is unavailable.");
        }
        return new OrderAudience(orderId, buyerId, businesses);
    }

    public String returnBusinessOrderId(String returnId) {
        return jdbc.queryForObject("SELECT business_order_id FROM business_order_returns WHERE id=?",
                String.class, returnId);
    }

    public void published(String eventId, Instant now) {
        jdbc.update("""
                UPDATE order_outbox_events
                SET notification_published_at = ?, notification_attempt_count = notification_attempt_count + 1,
                    notification_last_error_code = NULL
                WHERE id = ? AND notification_published_at IS NULL
                """, Timestamp.from(now), eventId);
    }

    public void failed(String eventId, Instant nextAttemptAt, String code) {
        jdbc.update("""
                UPDATE order_outbox_events
                SET notification_attempt_count = notification_attempt_count + 1,
                    notification_next_attempt_at = ?, notification_last_error_code = ?
                WHERE id = ? AND notification_published_at IS NULL
                """, Timestamp.from(nextAttemptAt), code, eventId);
    }

    public record OutboxRecord(String id, String aggregateType, String aggregateId,
            String eventType, int eventVersion, String correlationId,
            String causationId, Instant occurredAt) {}
    public record BusinessAudience(String businessOrderId, String businessId, String storeDisplayName) {}
    public record OrderAudience(String orderId, String buyerId, List<BusinessAudience> businesses) {}
}
