package com.msb.ecom.payment_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentRefundRepository {

    private final JdbcTemplate jdbc;

    public PaymentRefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IntentRecord> lockIntent(String paymentIntentId) {
        return jdbc.query("""
                        SELECT id, amount, currency, provider, provider_reference, status
                        FROM payment_intents WHERE id = ? FOR UPDATE
                        """,
                (rs, rowNum) -> new IntentRecord(
                        rs.getString("id"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("provider"), rs.getString("provider_reference"),
                        rs.getString("status")), paymentIntentId).stream().findFirst();
    }

    public Optional<RefundRecord> findByIntent(String paymentIntentId) {
        return query("SELECT * FROM payment_refunds WHERE payment_intent_id = ?", paymentIntentId);
    }

    public Optional<RefundRecord> findByKey(String idempotencyKey) {
        return query("SELECT * FROM payment_refunds WHERE idempotency_key = ?", idempotencyKey);
    }

    public Optional<RefundRecord> findByProviderReference(String provider, String reference) {
        return query("SELECT * FROM payment_refunds WHERE provider = ? AND provider_reference = ?",
                provider, reference);
    }

    public Optional<RefundRecord> lock(String refundId) {
        return query("SELECT * FROM payment_refunds WHERE id = ? FOR UPDATE", refundId);
    }

    public List<RefundRecord> due(Instant now, int limit) {
        return jdbc.query("""
                        SELECT * FROM payment_refunds
                        WHERE status IN ('PENDING','PROCESSING') AND next_reconcile_at <= ?
                        ORDER BY next_reconcile_at, id LIMIT ?
                        """, (rs, rowNum) -> map(rs), Timestamp.from(now), limit);
    }

    // Locks the intent before callers use this cross-refund total as the concurrency boundary.
    public BigDecimal reservedAmount(String paymentIntentId) {
        BigDecimal amount = jdbc.queryForObject("""
                SELECT COALESCE((SELECT SUM(amount) FROM payment_refunds
                    WHERE payment_intent_id = ? AND status IN ('PENDING','PROCESSING','SUCCEEDED')),0)
                     + COALESCE((SELECT SUM(amount) FROM payment_return_refunds
                    WHERE payment_intent_id = ? AND status IN ('PENDING','PROCESSING','SUCCEEDED')),0)
                """, BigDecimal.class, paymentIntentId, paymentIntentId);
        return amount == null ? BigDecimal.ZERO : amount;
    }

    public void reserve(
            String id, IntentRecord intent, String cancellationRequestId, String orderId,
            String idempotencyKey, String correlationId, String historyId, Instant now) {
        jdbc.update("""
                        INSERT INTO payment_refunds (
                            id, payment_intent_id, cancellation_request_id, order_id,
                            idempotency_key, amount, currency, provider, provider_reference,
                            status, created_at, completed_at, failed_at, next_reconcile_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING', ?, NULL, NULL, ?, ?)
                        """, id, intent.id(), cancellationRequestId, orderId, idempotencyKey,
                intent.amount(), intent.currency(), intent.provider(), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
        // Keep the deterministic local adapter's historical single-step refund audit unchanged.
        if (!"FAKE_LOCAL_DEMO_V1".equals(intent.provider())) {
            insertHistory(historyId, id, null, "PENDING", "REFUND_RESERVED", correlationId, now);
        }
    }

    public void applyProviderResult(
            String refundId, String status, String providerReference, String safeFailureCode,
            String operation, String correlationId, String attemptId, String historyId,
            String outboxId, String payload, Instant now, Instant nextReconcileAt) {
        RefundRecord current = lock(refundId).orElseThrow();
        if ("SUCCEEDED".equals(current.status()) || "FAILED".equals(current.status())) {
            return;
        }
        boolean stateChanged = !current.status().equals(status);
        jdbc.update("""
                        UPDATE payment_refunds
                        SET status = ?, provider_reference = COALESCE(provider_reference, ?),
                            completed_at = ?, failed_at = ?, safe_failure_code = ?,
                            next_reconcile_at = ?, updated_at = ?
                        WHERE id = ? AND status IN ('PENDING','PROCESSING')
                        """, status, providerReference,
                "SUCCEEDED".equals(status) ? Timestamp.from(now) : null,
                "FAILED".equals(status) ? Timestamp.from(now) : null,
                safeFailureCode,
                "PROCESSING".equals(status) ? Timestamp.from(nextReconcileAt) : null,
                Timestamp.from(now), refundId);
        jdbc.update("""
                        INSERT INTO payment_refund_attempts (
                            id, refund_id, attempt_number, operation, outcome,
                            provider_reference, safe_failure_code, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, attemptId, refundId, nextAttempt(refundId), operation, status,
                providerReference, safeFailureCode, Timestamp.from(now));
        if (stateChanged) {
            insertHistory(historyId, refundId, current.status(), status,
                    "PROVIDER_REFUND_" + status, correlationId, now);
        }
        if ("SUCCEEDED".equals(status)) {
            insertOutbox(outboxId, current.paymentIntentId(), payload, correlationId,
                    refundId, now);
        }
    }

    private int nextAttempt(String refundId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_number),0)+1 FROM payment_refund_attempts WHERE refund_id=?",
                Integer.class, refundId);
        return value == null ? 1 : value;
    }

    private void insertHistory(
            String id, String refundId, String from, String to, String reason,
            String correlationId, Instant now) {
        jdbc.update("""
                        INSERT INTO payment_refund_status_history (
                            id, refund_id, from_status, to_status, reason_code,
                            correlation_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, refundId, from, to, reason, correlationId, Timestamp.from(now));
    }

    private void insertOutbox(
            String id, String paymentIntentId, String payload, String correlationId,
            String causationId, Instant now) {
        jdbc.update("""
                        INSERT INTO payment_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            producer, payload_json, correlation_id, causation_id,
                            occurred_at, created_at
                        ) VALUES (?, 'PAYMENT_INTENT', ?, 'payment.refunded', 1,
                            'payment-service', CAST(? AS JSON), ?, ?, ?, ?)
                        """, id, paymentIntentId, payload, correlationId, causationId,
                Timestamp.from(now), Timestamp.from(now));
    }

    private Optional<RefundRecord> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> map(rs), args).stream().findFirst();
    }

    private RefundRecord map(ResultSet rs) throws SQLException {
        return new RefundRecord(
                rs.getString("id"), rs.getString("payment_intent_id"),
                rs.getString("cancellation_request_id"), rs.getString("order_id"),
                rs.getString("idempotency_key"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("provider"),
                rs.getString("provider_reference"), rs.getString("status"),
                instant(rs.getTimestamp("completed_at")), rs.getString("safe_failure_code"));
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record IntentRecord(
            String id, BigDecimal amount, String currency, String provider,
            String providerReference, String status) {
    }

    public record RefundRecord(
            String id, String paymentIntentId, String cancellationRequestId, String orderId,
            String idempotencyKey, BigDecimal amount, String currency, String provider,
            String providerReference, String status, Instant completedAt, String safeFailureCode) {
    }
}
