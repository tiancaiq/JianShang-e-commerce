package com.msb.ecom.order_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderCancellationProcessingRepository {

    private final JdbcTemplate jdbc;

    public OrderCancellationProcessingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> pendingRequestIds(int limit) {
        return jdbc.query("""
                        SELECT id FROM order_cancellation_requests
                        WHERE status = 'PENDING'
                        ORDER BY requested_at, id LIMIT ?
                        """, (rs, rowNum) -> rs.getString("id"), limit);
    }

    public Optional<DecisionRecord> lockDecision(String requestId) {
        return jdbc.query("""
                        SELECT r.id AS request_id, r.order_id, r.correlation_id, r.causation_id,
                               o.checkout_id, o.payment_intent_id, o.total, o.currency, o.version,
                               c.reservation_id
                        FROM order_cancellation_requests r
                        JOIN orders o ON o.id = r.order_id
                        JOIN checkout_sessions c ON c.id = o.checkout_id
                        WHERE r.id = ? AND r.status = 'PENDING'
                          AND o.status = 'CANCELLATION_REQUESTED'
                        FOR UPDATE
                        """, (rs, rowNum) -> new DecisionRecord(
                        rs.getString("request_id"), rs.getString("order_id"),
                        rs.getString("correlation_id"), rs.getString("causation_id"),
                        rs.getString("checkout_id"), rs.getString("payment_intent_id"),
                        rs.getBigDecimal("total"), rs.getString("currency"),
                        rs.getLong("version"), rs.getString("reservation_id")), requestId)
                .stream().findFirst();
    }

    public List<GroupRecord> lockGroups(String orderId) {
        return jdbc.query("""
                        SELECT id, business_id, fulfillment_status, cancellation_status
                        FROM business_orders WHERE order_id = ? ORDER BY id FOR UPDATE
                        """, (rs, rowNum) -> new GroupRecord(
                        rs.getString("id"), rs.getString("business_id"),
                        rs.getString("fulfillment_status"), rs.getString("cancellation_status")),
                orderId);
    }

    public int cancelOrder(DecisionRecord record, Instant now) {
        return jdbc.update("""
                        UPDATE orders SET status = 'CANCELLED', version = version + 1, updated_at = ?
                        WHERE id = ? AND status = 'CANCELLATION_REQUESTED' AND version = ?
                        """, Timestamp.from(now), record.orderId(), record.orderVersion());
    }

    public int cancelGroup(String businessOrderId, Instant now) {
        return jdbc.update("""
                        UPDATE business_orders
                        SET cancellation_status = 'CANCELLED', updated_at = ?
                        WHERE id = ? AND fulfillment_status = 'PENDING_ACCEPTANCE'
                          AND cancellation_status = 'CANCELLATION_PENDING'
                        """, Timestamp.from(now), businessOrderId);
    }

    public int approveRequest(String requestId, Instant now) {
        return jdbc.update("""
                        UPDATE order_cancellation_requests
                        SET status = 'AUTO_APPROVED', decision_type = 'AUTO_BEFORE_FULFILLMENT',
                            decided_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """, Timestamp.from(now), requestId);
    }

    public void insertCompensation(
            String id, DecisionRecord record, Instant now) {
        jdbc.update("""
                        INSERT INTO order_cancellation_compensations (
                            id, cancellation_request_id, order_id, checkout_id, reservation_id,
                            payment_intent_id, amount, currency, inventory_status, refund_status,
                            retry_count, next_attempt_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'PENDING', 0, ?, ?, ?)
                        """, id, record.requestId(), record.orderId(), record.checkoutId(),
                record.reservationId(), record.paymentIntentId(), record.amount(), record.currency(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    public void insertOrderHistory(
            String id, DecisionRecord record, String causationId, Instant now) {
        jdbc.update("""
                        INSERT INTO order_status_history (
                            id, order_id, from_status, to_status, reason_code, actor_scope,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, ?, 'CANCELLATION_REQUESTED', 'CANCELLED',
                            'AUTO_APPROVED_BEFORE_FULFILLMENT', 'SYSTEM', ?, ?, ?)
                        """, id, record.orderId(), record.correlationId(), causationId,
                Timestamp.from(now));
    }

    public void insertGroupHistory(
            String id, DecisionRecord record, GroupRecord group, String causationId, Instant now) {
        jdbc.update("""
                        INSERT INTO business_order_cancellation_history (
                            id, business_order_id, business_id, cancellation_request_id,
                            from_status, to_status, reason_code, correlation_id, causation_id,
                            created_at
                        ) VALUES (?, ?, ?, ?, 'CANCELLATION_PENDING', 'CANCELLED',
                            'AUTO_APPROVED_BEFORE_FULFILLMENT', ?, ?, ?)
                        """, id, group.businessOrderId(), group.businessId(), record.requestId(),
                record.correlationId(), causationId, Timestamp.from(now));
    }

    public void insertOutbox(
            String id, String orderId, String eventType, String payload,
            String correlationId, String causationId, Instant now) {
        jdbc.update("""
                        INSERT INTO order_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            payload_json, correlation_id, causation_id, created_at
                        ) VALUES (?, 'ORDER', ?, ?, 1, CAST(? AS JSON), ?, ?, ?)
                        """, id, orderId, eventType, payload, correlationId, causationId,
                Timestamp.from(now));
    }

    public List<CompensationRecord> dueCompensations(Instant now, int limit) {
        return jdbc.query("""
                        SELECT * FROM order_cancellation_compensations
                        WHERE completed_at IS NULL AND next_attempt_at <= ?
                        ORDER BY next_attempt_at, id LIMIT ?
                        """, (rs, rowNum) -> compensation(rs), Timestamp.from(now), limit);
    }

    public Optional<CompensationRecord> findCompensation(String id) {
        return jdbc.query("SELECT * FROM order_cancellation_compensations WHERE id = ?",
                (rs, rowNum) -> compensation(rs), id).stream().findFirst();
    }

    public int inventorySucceeded(String id, Instant now) {
        return jdbc.update("""
                        UPDATE order_cancellation_compensations
                        SET inventory_status = 'SUCCEEDED', last_error_code = NULL, updated_at = ?
                        WHERE id = ? AND inventory_status = 'PENDING'
                        """, Timestamp.from(now), id);
    }

    public int refundSucceeded(
            String id, String refundId, String refundReference, Instant now) {
        return jdbc.update("""
                        UPDATE order_cancellation_compensations
                        SET refund_status = 'SUCCEEDED', refund_id = ?, refund_reference = ?,
                            last_error_code = NULL, updated_at = ?
                        WHERE id = ? AND refund_status = 'PENDING'
                        """, refundId, refundReference, Timestamp.from(now), id);
    }

    public void retryLater(String id, String code, Instant nextAttemptAt, Instant now) {
        jdbc.update("""
                        UPDATE order_cancellation_compensations
                        SET retry_count = retry_count + 1, last_error_code = ?,
                            next_attempt_at = ?, updated_at = ?
                        WHERE id = ? AND completed_at IS NULL
                        """, code, Timestamp.from(nextAttemptAt), Timestamp.from(now), id);
    }

    public int complete(String id, Instant now) {
        return jdbc.update("""
                        UPDATE order_cancellation_compensations
                        SET completed_at = ?, updated_at = ?
                        WHERE id = ? AND completed_at IS NULL
                          AND inventory_status = 'SUCCEEDED' AND refund_status = 'SUCCEEDED'
                        """, Timestamp.from(now), Timestamp.from(now), id);
    }

    public int completeRequest(String requestId, Instant now) {
        return jdbc.update("""
                        UPDATE order_cancellation_requests SET status = 'COMPLETED', completed_at = ?
                        WHERE id = ? AND status = 'AUTO_APPROVED'
                        """, Timestamp.from(now), requestId);
    }

    private CompensationRecord compensation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CompensationRecord(
                rs.getString("id"), rs.getString("cancellation_request_id"),
                rs.getString("order_id"), rs.getString("reservation_id"),
                rs.getString("payment_intent_id"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("inventory_status"),
                rs.getString("refund_status"), rs.getString("refund_id"),
                rs.getString("refund_reference"), rs.getInt("retry_count"));
    }

    public record DecisionRecord(
            String requestId, String orderId, String correlationId, String causationId,
            String checkoutId, String paymentIntentId, BigDecimal amount, String currency,
            long orderVersion, String reservationId) {
    }

    public record GroupRecord(
            String businessOrderId, String businessId,
            String fulfillmentStatus, String cancellationStatus) {
    }

    public record CompensationRecord(
            String id, String requestId, String orderId, String reservationId,
            String paymentIntentId, BigDecimal amount, String currency,
            String inventoryStatus, String refundStatus, String refundId,
            String refundReference, int retryCount) {
    }
}
