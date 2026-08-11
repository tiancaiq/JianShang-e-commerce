package com.msb.ecom.payment_service.repository;

import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.model.PaymentWebhookOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentIntentRepository {

    private final JdbcTemplate jdbc;

    public PaymentIntentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Persists the authoritative checkout reference and all business scopes atomically.
    public void insert(PaymentIntent intent, String historyId, String correlationId) {
        jdbc.update("""
                        INSERT INTO payment_intents (
                            id, checkout_id, checkout_version, checkout_snapshot_hash,
                            buyer_id, caller_scope, amount, currency, payment_method_type,
                            capture_method, merchant_of_record, funds_flow, provider,
                            provider_reference, provider_action_type, status, version,
                            expires_at, safe_error_code, safe_error_message, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                intent.id(), intent.checkoutId(), intent.checkoutVersion(), intent.checkoutSnapshotHash(),
                intent.buyerId(), intent.callerScope(), intent.amount(), intent.currency(),
                intent.paymentMethodType(), intent.captureMethod(), intent.merchantOfRecord(),
                intent.fundsFlow(), intent.provider(),
                intent.providerReference(), intent.providerActionType(), intent.status().name(), intent.version(),
                Timestamp.from(intent.expiresAt()), intent.safeErrorCode(), intent.safeErrorMessage(),
                Timestamp.from(intent.createdAt()), Timestamp.from(intent.updatedAt()));
        for (int index = 0; index < intent.businessIds().size(); index++) {
            jdbc.update("""
                            INSERT INTO payment_intent_business_scopes (
                                payment_intent_id, business_id, scope_order
                            ) VALUES (?, ?, ?)
                            """,
                    intent.id(), intent.businessIds().get(index), index);
        }
        insertHistory(
                historyId, intent.id(), null, intent.status(), "INTENT_CREATED",
                intent.callerScope(), correlationId, intent.createdAt());
    }

    public Optional<PaymentIntent> find(String paymentIntentId) {
        return withBusinessScopes(jdbc.query(
                "SELECT * FROM payment_intents WHERE id = ?",
                (rs, rowNum) -> map(rs),
                paymentIntentId).stream().findFirst());
    }

    public Optional<PaymentIntent> findByCheckout(String checkoutId) {
        return withBusinessScopes(jdbc.query(
                "SELECT * FROM payment_intents WHERE checkout_id = ?",
                (rs, rowNum) -> map(rs),
                checkoutId).stream().findFirst());
    }

    public List<PaymentIntent> reconciliationCandidates(
            String provider, Instant updatedBefore, int limit) {
        return jdbc.query("""
                        SELECT * FROM payment_intents
                        WHERE provider = ? AND provider_reference IS NOT NULL
                          AND status IN ('REQUIRES_ACTION','PROCESSING')
                          AND updated_at <= ?
                        ORDER BY updated_at, id LIMIT ?
                        """, (rs, rowNum) -> map(rs), provider, Timestamp.from(updatedBefore), limit)
                .stream().map(intent -> withBusinessScopes(Optional.of(intent)).orElseThrow()).toList();
    }

    public Optional<PaymentIntent> findOwned(String paymentIntentId, String buyerId) {
        return withBusinessScopes(jdbc.query(
                "SELECT * FROM payment_intents WHERE id = ? AND buyer_id = ?",
                (rs, rowNum) -> map(rs),
                paymentIntentId, buyerId).stream().findFirst());
    }

    public Optional<PaymentIntent> findByProviderReferenceForUpdate(
            String provider,
            String providerReference) {
        return withBusinessScopes(jdbc.query("""
                        SELECT * FROM payment_intents
                        WHERE provider = ? AND provider_reference = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> map(rs),
                provider,
                providerReference).stream().findFirst());
    }

    // Uses the aggregate version and source status to reject concurrent state writers.
    public int transition(
            PaymentIntent current,
            PaymentIntent next,
            String historyId,
            String reasonCode,
            String correlationId) {
        return transitionAs(
                current,
                next,
                historyId,
                reasonCode,
                current.callerScope(),
                correlationId);
    }

    public int transitionAs(
            PaymentIntent current,
            PaymentIntent next,
            String historyId,
            String reasonCode,
            String actorScope,
            String correlationId) {
        int changed = jdbc.update("""
                        UPDATE payment_intents
                        SET provider_reference = ?,
                            provider_action_type = ?,
                            status = ?,
                            version = ?,
                            safe_error_code = ?,
                            safe_error_message = ?,
                            updated_at = ?
                        WHERE id = ? AND version = ? AND status = ?
                        """,
                next.providerReference(), next.providerActionType(), next.status().name(), next.version(),
                next.safeErrorCode(), next.safeErrorMessage(), Timestamp.from(next.updatedAt()),
                current.id(), current.version(), current.status().name());
        if (changed == 1) {
            insertHistory(
                    historyId, current.id(), current.status(), next.status(), reasonCode,
                    actorScope, correlationId, next.updatedAt());
        }
        return changed;
    }

    public void insertAttempt(
            String id,
            String paymentIntentId,
            int attemptNumber,
            String provider,
            String outcome,
            String providerReference,
            String safeErrorCode,
            String safeErrorMessage,
            Instant createdAt) {
        jdbc.update("""
                        INSERT INTO payment_attempts (
                            id, payment_intent_id, attempt_number, provider, operation,
                            outcome, provider_reference, safe_error_code, safe_error_message, created_at
                        ) VALUES (?, ?, ?, ?, 'CREATE_INTENT', ?, ?, ?, ?, ?)
                        """,
                id, paymentIntentId, attemptNumber, provider, outcome, providerReference,
                safeErrorCode, safeErrorMessage, Timestamp.from(createdAt));
    }

    public void insertWebhookAttempt(
            String id,
            String paymentIntentId,
            int attemptNumber,
            String provider,
            String outcome,
            String providerReference,
            String safeErrorCode,
            String safeErrorMessage,
            Instant createdAt) {
        jdbc.update("""
                        INSERT INTO payment_attempts (
                            id, payment_intent_id, attempt_number, provider, operation,
                            outcome, provider_reference, safe_error_code, safe_error_message, created_at
                        ) VALUES (?, ?, ?, ?, 'WEBHOOK_CONFIRMATION', ?, ?, ?, ?, ?)
                        """,
                id, paymentIntentId, attemptNumber, provider, outcome, providerReference,
                safeErrorCode, safeErrorMessage, Timestamp.from(createdAt));
    }

    public void insertReconciliationAttempt(
            String id, String paymentIntentId, int attemptNumber, String provider,
            String outcome, String providerReference, String safeErrorCode,
            String safeErrorMessage, Instant createdAt) {
        jdbc.update("""
                        INSERT INTO payment_attempts (
                            id, payment_intent_id, attempt_number, provider, operation,
                            outcome, provider_reference, safe_error_code, safe_error_message, created_at
                        ) VALUES (?, ?, ?, ?, 'RECONCILE_INTENT', ?, ?, ?, ?, ?)
                        """, id, paymentIntentId, attemptNumber, provider, outcome,
                providerReference, safeErrorCode, safeErrorMessage, Timestamp.from(createdAt));
    }

    public int nextAttemptNumber(String paymentIntentId) {
        Integer result = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM payment_attempts WHERE payment_intent_id = ?",
                Integer.class,
                paymentIntentId);
        return result == null ? 1 : result;
    }

    public Optional<PaymentIdempotencyRecord> idempotency(String callerScope, String key) {
        return jdbc.query("""
                        SELECT caller_scope, idempotency_key, request_hash, state,
                               payment_intent_id, http_status
                        FROM payment_idempotency_records
                        WHERE caller_scope = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new PaymentIdempotencyRecord(
                        rs.getString("caller_scope"),
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getString("state"),
                        rs.getString("payment_intent_id"),
                        (Integer) rs.getObject("http_status")),
                callerScope,
                key).stream().findFirst();
    }

    public void insertIdempotency(
            String id,
            String callerScope,
            String key,
            String requestHash,
            String paymentIntentId,
            Instant now,
            Instant expiresAt) {
        jdbc.update("""
                        INSERT INTO payment_idempotency_records (
                            id, caller_scope, idempotency_key, request_hash, operation,
                            state, payment_intent_id, created_at, updated_at, expires_at
                        ) VALUES (?, ?, ?, ?, 'CREATE_PAYMENT_INTENT',
                                  'IN_PROGRESS', ?, ?, ?, ?)
                        """,
                id, callerScope, key, requestHash, paymentIntentId,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt));
    }

    public void completeIdempotency(String callerScope, String key, Instant now) {
        jdbc.update("""
                        UPDATE payment_idempotency_records
                        SET state = 'COMPLETED', http_status = 201, updated_at = ?
                        WHERE caller_scope = ? AND idempotency_key = ? AND state = 'IN_PROGRESS'
                        """,
                Timestamp.from(now), callerScope, key);
    }

    public List<String> historyStatuses(String paymentIntentId) {
        return jdbc.query("""
                        SELECT to_status FROM payment_status_history
                        WHERE payment_intent_id = ?
                        ORDER BY created_at, id
                        """,
                (rs, rowNum) -> rs.getString(1),
                paymentIntentId);
    }

    public int attemptCount(String paymentIntentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_attempts WHERE payment_intent_id = ?",
                Integer.class,
                paymentIntentId);
        return count == null ? 0 : count;
    }

    public Optional<PaymentProviderEventRecord> providerEvent(
            String provider,
            String providerEventId) {
        return jdbc.query("""
                        SELECT provider, provider_event_id, event_type, provider_reference,
                               payment_intent_id, refund_id, payload_hash, processing_outcome, resulting_status
                        FROM payment_provider_events
                        WHERE provider = ? AND provider_event_id = ?
                        """,
                (rs, rowNum) -> new PaymentProviderEventRecord(
                        rs.getString("provider"),
                        rs.getString("provider_event_id"),
                        rs.getString("event_type"),
                        rs.getString("provider_reference"),
                        rs.getString("payment_intent_id"),
                        rs.getString("refund_id"),
                        rs.getString("payload_hash"),
                        PaymentWebhookOutcome.valueOf(rs.getString("processing_outcome")),
                        rs.getString("resulting_status")),
                provider,
                providerEventId).stream().findFirst();
    }

    public void insertProviderEvent(
            String id,
            String provider,
            String providerEventId,
            String eventType,
            String providerReference,
            String paymentIntentId,
            String refundId,
            String payloadHash,
            Instant signatureTimestamp,
            Instant providerOccurredAt,
            PaymentWebhookOutcome outcome,
            String resultingStatus,
            String safeErrorCode,
            String correlationId,
            Instant createdAt) {
        jdbc.update("""
                        INSERT INTO payment_provider_events (
                            id, provider, provider_event_id, event_type, provider_reference,
                            payment_intent_id, refund_id, payload_hash, signature_timestamp,
                            provider_occurred_at, processing_outcome, resulting_status,
                            safe_error_code, correlation_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, provider, providerEventId, eventType, providerReference, paymentIntentId,
                refundId, payloadHash, Timestamp.from(signatureTimestamp), Timestamp.from(providerOccurredAt),
                outcome.name(), resultingStatus,
                safeErrorCode, correlationId, Timestamp.from(createdAt));
    }

    public void insertOutbox(
            String id,
            String paymentIntentId,
            String eventType,
            String payloadJson,
            String correlationId,
            String causationId,
            Instant occurredAt,
            Instant createdAt) {
        jdbc.update("""
                        INSERT INTO payment_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            producer, payload_json, correlation_id, causation_id,
                            occurred_at, created_at, retry_count, next_attempt_at
                        ) VALUES (?, 'payment', ?, ?, 1, 'payment-service', CAST(? AS JSON),
                                  ?, ?, ?, ?, 0, ?)
                        """,
                id, paymentIntentId, eventType, payloadJson, correlationId, causationId,
                Timestamp.from(occurredAt), Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private void insertHistory(
            String id,
            String paymentIntentId,
            PaymentIntentStatus from,
            PaymentIntentStatus to,
            String reasonCode,
            String actorScope,
            String correlationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO payment_status_history (
                            id, payment_intent_id, from_status, to_status, reason_code,
                            actor_scope, correlation_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, paymentIntentId, from == null ? null : from.name(), to.name(), reasonCode,
                actorScope, correlationId, Timestamp.from(now));
    }

    private PaymentIntent map(ResultSet rs) throws SQLException {
        return new PaymentIntent(
                rs.getString("id"),
                rs.getString("checkout_id"),
                rs.getLong("checkout_version"),
                rs.getString("checkout_snapshot_hash"),
                rs.getString("buyer_id"),
                rs.getString("caller_scope"),
                List.of(),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("payment_method_type"),
                rs.getString("capture_method"),
                rs.getString("merchant_of_record"),
                rs.getString("funds_flow"),
                rs.getString("provider"),
                rs.getString("provider_reference"),
                rs.getString("provider_action_type"),
                PaymentIntentStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("safe_error_code"),
                rs.getString("safe_error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    // Loads child scopes only after MySQL has closed the parent result set on the transaction connection.
    private Optional<PaymentIntent> withBusinessScopes(Optional<PaymentIntent> intent) {
        return intent.map(current -> new PaymentIntent(
                current.id(), current.checkoutId(), current.checkoutVersion(),
                current.checkoutSnapshotHash(), current.buyerId(), current.callerScope(),
                businessIds(current.id()), current.amount(), current.currency(),
                current.paymentMethodType(), current.captureMethod(), current.merchantOfRecord(),
                current.fundsFlow(), current.provider(), current.providerReference(),
                current.providerActionType(), current.status(), current.version(), current.expiresAt(),
                current.safeErrorCode(), current.safeErrorMessage(), current.createdAt(), current.updatedAt()));
    }

    private List<String> businessIds(String paymentIntentId) {
        return jdbc.query("""
                        SELECT business_id FROM payment_intent_business_scopes
                        WHERE payment_intent_id = ?
                        ORDER BY scope_order
                        """,
                (rs, rowNum) -> rs.getString(1),
                paymentIntentId);
    }
}
