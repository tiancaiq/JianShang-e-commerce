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
public class PaymentReturnRefundRepository {

    private final JdbcTemplate jdbc;

    public PaymentReturnRefundRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Record> findByReturn(String id) {
        return query("SELECT * FROM payment_return_refunds WHERE return_id=?", id);
    }

    public Optional<Record> findByKey(String id) {
        return query("SELECT * FROM payment_return_refunds WHERE idempotency_key=?", id);
    }

    public Optional<Record> findByProviderReference(String provider, String reference) {
        return query("SELECT * FROM payment_return_refunds WHERE provider=? AND provider_reference=?",
                provider, reference);
    }

    public Optional<Record> lock(String id) {
        return query("SELECT * FROM payment_return_refunds WHERE id=? FOR UPDATE", id);
    }

    public List<Record> due(Instant now, int limit) {
        return jdbc.query("""
                SELECT * FROM payment_return_refunds
                WHERE status IN ('PENDING','PROCESSING') AND next_reconcile_at <= ?
                ORDER BY next_reconcile_at,id LIMIT ?
                """, (rs, rowNum) -> map(rs), Timestamp.from(now), limit);
    }

    public void reserve(
            String id, String intentId, String returnId, String orderId, String businessOrderId,
            String key, BigDecimal amount, String currency, String provider,
            String correlationId, Instant now) {
        jdbc.update("""
                INSERT INTO payment_return_refunds(
                    id,payment_intent_id,return_id,order_id,business_order_id,idempotency_key,
                    amount,currency,provider,provider_reference,status,created_at,completed_at,
                    failed_at,next_reconcile_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,NULL,'PENDING',?,NULL,NULL,?,?)
                """, id, intentId, returnId, orderId, businessOrderId, key, amount, currency,
                provider, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    public void applyProviderResult(
            String refundId, String status, String providerReference, String safeFailureCode,
            String operation, String correlationId, String attemptId, String outboxId,
            String payload, Instant now, Instant nextReconcileAt) {
        Record current = lock(refundId).orElseThrow();
        if ("SUCCEEDED".equals(current.status()) || "FAILED".equals(current.status())) {
            return;
        }
        jdbc.update("""
                UPDATE payment_return_refunds
                SET status=?,provider_reference=COALESCE(provider_reference,?),completed_at=?,
                    failed_at=?,safe_failure_code=?,next_reconcile_at=?,updated_at=?
                WHERE id=? AND status IN ('PENDING','PROCESSING')
                """, status, providerReference,
                "SUCCEEDED".equals(status) ? Timestamp.from(now) : null,
                "FAILED".equals(status) ? Timestamp.from(now) : null,
                safeFailureCode,
                "PROCESSING".equals(status) ? Timestamp.from(nextReconcileAt) : null,
                Timestamp.from(now), refundId);
        jdbc.update("""
                INSERT INTO payment_return_refund_attempts(
                    id,refund_id,attempt_number,operation,outcome,provider_reference,
                    safe_failure_code,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, attemptId, refundId, nextAttempt(refundId), operation, status,
                providerReference, safeFailureCode, Timestamp.from(now));
        if ("SUCCEEDED".equals(status)) {
            jdbc.update("""
                    INSERT INTO payment_outbox_events(
                        id,aggregate_type,aggregate_id,event_type,event_version,producer,
                        payload_json,correlation_id,causation_id,occurred_at,created_at)
                    VALUES (?,'PAYMENT_INTENT',?,'payment.refunded',1,'payment-service',
                        CAST(? AS JSON),?,?,?,?)
                    """, outboxId, current.paymentIntentId(), payload, correlationId,
                    current.returnId(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    private int nextAttempt(String refundId) {
        Integer result = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number),0)+1
                FROM payment_return_refund_attempts WHERE refund_id=?
                """, Integer.class, refundId);
        return result == null ? 1 : result;
    }

    private Optional<Record> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> map(rs), args).stream().findFirst();
    }

    private Record map(ResultSet rs) throws SQLException {
        return new Record(
                rs.getString("id"), rs.getString("payment_intent_id"), rs.getString("return_id"),
                rs.getString("order_id"), rs.getString("business_order_id"),
                rs.getString("idempotency_key"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("provider"),
                rs.getString("provider_reference"), rs.getString("status"),
                instant(rs.getTimestamp("completed_at")), rs.getString("safe_failure_code"));
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Record(
            String id, String paymentIntentId, String returnId, String orderId,
            String businessOrderId, String key, BigDecimal amount, String currency,
            String provider, String providerReference, String status, Instant completedAt,
            String safeFailureCode) {
    }
}
