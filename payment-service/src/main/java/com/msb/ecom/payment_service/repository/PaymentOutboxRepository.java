package com.msb.ecom.payment_service.repository;

import com.msb.ecom.payment_service.outbox.PaymentOutboxRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class PaymentOutboxRepository {

    private final JdbcTemplate jdbc;

    public PaymentOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Claims due rows in stable creation order under a bounded lease.
    public List<PaymentOutboxRecord> claim(
            String claimToken,
            Instant now,
            Instant claimExpiresAt,
            int batchSize) {
        List<String> ids = jdbc.query("""
                        SELECT id
                        FROM payment_outbox_events
                        WHERE published_at IS NULL
                          AND terminal_failure_at IS NULL
                          AND event_type IN ('payment.succeeded', 'payment.failed')
                          AND next_attempt_at <= ?
                          AND (claim_expires_at IS NULL OR claim_expires_at <= ?)
                        ORDER BY created_at, id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, rowNum) -> rs.getString(1),
                Timestamp.from(now),
                Timestamp.from(now),
                batchSize);
        for (String id : ids) {
            jdbc.update("""
                            UPDATE payment_outbox_events
                            SET claim_token = ?, claimed_at = ?, claim_expires_at = ?
                            WHERE id = ?
                              AND published_at IS NULL
                              AND terminal_failure_at IS NULL
                              AND event_type IN ('payment.succeeded', 'payment.failed')
                            """,
                    claimToken,
                    Timestamp.from(now),
                    Timestamp.from(claimExpiresAt),
                    id);
        }
        return jdbc.query("""
                        SELECT id, aggregate_type, aggregate_id, event_type, event_version,
                               producer, payload_json, correlation_id, causation_id,
                               occurred_at, created_at, retry_count, attempt_count
                        FROM payment_outbox_events
                        WHERE claim_token = ?
                        ORDER BY created_at, id
                        """,
                (rs, rowNum) -> new PaymentOutboxRecord(
                        rs.getString("id"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("producer"),
                        rs.getString("payload_json"),
                        rs.getString("correlation_id"),
                        rs.getString("causation_id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("retry_count"),
                        rs.getInt("attempt_count")),
                claimToken);
    }

    // Completes only the lease still owned by this worker after transport acknowledgement.
    public int markPublished(String eventId, String claimToken, Instant now) {
        return jdbc.update("""
                        UPDATE payment_outbox_events
                        SET published_at = ?,
                            attempt_count = attempt_count + 1,
                            last_attempt_at = ?,
                            next_attempt_at = NULL,
                            claim_token = NULL,
                            claimed_at = NULL,
                            claim_expires_at = NULL,
                            last_error_code = NULL,
                            last_error_message = NULL
                        WHERE id = ?
                          AND claim_token = ?
                          AND published_at IS NULL
                          AND terminal_failure_at IS NULL
                        """,
                Timestamp.from(now),
                Timestamp.from(now),
                eventId,
                claimToken);
    }

    // Releases the owned lease into bounded retry or terminal failure state.
    public int markFailed(
            String eventId,
            String claimToken,
            Instant now,
            Instant nextAttemptAt,
            boolean terminal,
            String safeErrorCode,
            String safeErrorMessage) {
        return jdbc.update("""
                        UPDATE payment_outbox_events
                        SET retry_count = retry_count + 1,
                            attempt_count = attempt_count + 1,
                            last_attempt_at = ?,
                            next_attempt_at = ?,
                            terminal_failure_at = ?,
                            claim_token = NULL,
                            claimed_at = NULL,
                            claim_expires_at = NULL,
                            last_error_code = ?,
                            last_error_message = ?
                        WHERE id = ?
                          AND claim_token = ?
                          AND published_at IS NULL
                          AND terminal_failure_at IS NULL
                        """,
                Timestamp.from(now),
                terminal ? null : Timestamp.from(nextAttemptAt),
                terminal ? Timestamp.from(now) : null,
                safeErrorCode,
                safeErrorMessage,
                eventId,
                claimToken);
    }
}
