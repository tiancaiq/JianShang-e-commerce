package com.msb.ecom.payment_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class PaymentRefundRepository {

    private final JdbcTemplate jdbc;

    public PaymentRefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IntentRecord> lockIntent(String paymentIntentId) {
        return jdbc.query("""
                        SELECT id, amount, currency, provider, status
                        FROM payment_intents WHERE id = ? FOR UPDATE
                        """,
                (rs, rowNum) -> new IntentRecord(
                        rs.getString("id"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("provider"),
                        rs.getString("status")), paymentIntentId).stream().findFirst();
    }

    public Optional<RefundRecord> findByIntent(String paymentIntentId) {
        return query("SELECT * FROM payment_refunds WHERE payment_intent_id = ?", paymentIntentId);
    }

    public Optional<RefundRecord> findByKey(String idempotencyKey) {
        return query("SELECT * FROM payment_refunds WHERE idempotency_key = ?", idempotencyKey);
    }

    public void insert(
            String id,
            String paymentIntentId,
            String cancellationRequestId,
            String orderId,
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            String provider,
            String providerReference,
            String correlationId,
            String attemptId,
            String historyId,
            String outboxId,
            String payload,
            Instant now) {
        jdbc.update("""
                        INSERT INTO payment_refunds (
                            id, payment_intent_id, cancellation_request_id, order_id,
                            idempotency_key, amount, currency, provider, provider_reference,
                            status, created_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?)
                        """, id, paymentIntentId, cancellationRequestId, orderId,
                idempotencyKey, amount, currency, provider, providerReference,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                        INSERT INTO payment_refund_attempts (
                            id, refund_id, attempt_number, operation, outcome,
                            provider_reference, created_at
                        ) VALUES (?, ?, 1, 'FULL_REFUND', 'SUCCEEDED', ?, ?)
                        """, attemptId, id, providerReference, Timestamp.from(now));
        jdbc.update("""
                        INSERT INTO payment_refund_status_history (
                            id, refund_id, from_status, to_status, reason_code,
                            correlation_id, created_at
                        ) VALUES (?, ?, NULL, 'SUCCEEDED', 'LOCAL_DEMO_FULL_REFUND', ?, ?)
                        """, historyId, id, correlationId, Timestamp.from(now));
        jdbc.update("""
                        INSERT INTO payment_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            producer, payload_json, correlation_id, causation_id,
                            occurred_at, created_at
                        ) VALUES (?, 'PAYMENT_INTENT', ?, 'payment.refunded', 1,
                            'payment-service', CAST(? AS JSON), ?, ?, ?, ?)
                        """, outboxId, paymentIntentId, payload, correlationId,
                cancellationRequestId, Timestamp.from(now), Timestamp.from(now));
    }

    private Optional<RefundRecord> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> new RefundRecord(
                        rs.getString("id"), rs.getString("payment_intent_id"),
                        rs.getString("cancellation_request_id"), rs.getString("order_id"),
                        rs.getString("idempotency_key"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("provider"),
                        rs.getString("provider_reference"), rs.getString("status"),
                        rs.getTimestamp("completed_at").toInstant()), args)
                .stream().findFirst();
    }

    public record IntentRecord(
            String id, BigDecimal amount, String currency, String provider, String status) {
    }

    public record RefundRecord(
            String id,
            String paymentIntentId,
            String cancellationRequestId,
            String orderId,
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            String provider,
            String providerReference,
            String status,
            Instant completedAt
    ) {
    }
}
