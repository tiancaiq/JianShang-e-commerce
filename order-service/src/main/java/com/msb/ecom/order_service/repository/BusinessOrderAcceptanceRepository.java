package com.msb.ecom.order_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class BusinessOrderAcceptanceRepository {

    private final JdbcTemplate jdbc;

    public BusinessOrderAcceptanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int purgeExpired(Instant now, int limit) {
        return jdbc.update("""
                DELETE FROM business_order_acceptance_commands
                WHERE expires_at < ?
                ORDER BY expires_at, id
                LIMIT ?
                """, Timestamp.from(now), limit);
    }

    public Optional<BusinessOrderAcceptanceCommand> lockCommand(
            String actorUserId,
            String businessId,
            String operation,
            String idempotencyKey) {
        return jdbc.query("""
                        SELECT id, request_hash, state, business_order_id,
                               result_version, result_updated_at
                        FROM business_order_acceptance_commands
                        WHERE actor_user_id = ? AND business_id = ?
                          AND operation = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new BusinessOrderAcceptanceCommand(
                        rs.getString("id"),
                        rs.getString("request_hash"),
                        rs.getString("state"),
                        rs.getString("business_order_id"),
                        (Long) rs.getObject("result_version"),
                        rs.getTimestamp("result_updated_at") == null
                                ? null
                                : rs.getTimestamp("result_updated_at").toInstant()),
                actorUserId,
                businessId,
                operation,
                idempotencyKey).stream().findFirst();
    }

    public void insertCommand(
            String id,
            String actorUserId,
            String businessId,
            String operation,
            String idempotencyKey,
            String requestHash,
            String businessOrderId,
            Instant now,
            Instant expiresAt) {
        jdbc.update("""
                        INSERT INTO business_order_acceptance_commands (
                            id, actor_user_id, business_id, operation, idempotency_key,
                            request_hash, state, business_order_id, created_at, updated_at,
                            expires_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
                        """,
                id,
                actorUserId,
                businessId,
                operation,
                idempotencyKey,
                requestHash,
                businessOrderId,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(expiresAt));
    }

    // Locks the SQL-owned group and its paid parent without crossing service schemas.
    public Optional<GroupState> lockOwnedGroup(String businessId, String businessOrderId) {
        return jdbc.query("""
                        SELECT bo.id, bo.order_id, bo.business_id, bo.fulfillment_status,
                               bo.cancellation_status, bo.version, bo.updated_at,
                               o.payment_status
                        FROM business_orders bo
                        JOIN orders o ON o.id = bo.order_id
                        WHERE bo.business_id = ? AND bo.id = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new GroupState(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getString("business_id"),
                        rs.getString("fulfillment_status"),
                        rs.getString("cancellation_status"),
                        rs.getLong("version"),
                        rs.getString("payment_status"),
                        rs.getTimestamp("updated_at").toInstant()),
                businessId,
                businessOrderId).stream().findFirst();
    }

    public int accept(
            String businessId,
            String businessOrderId,
            long expectedVersion,
            Instant now) {
        return jdbc.update("""
                        UPDATE business_orders
                        SET fulfillment_status = 'ACCEPTED',
                            version = version + 1,
                            updated_at = ?
                        WHERE business_id = ? AND id = ?
                          AND version = ?
                          AND fulfillment_status = 'PENDING_ACCEPTANCE'
                          AND cancellation_status = 'NONE'
                        """,
                Timestamp.from(now),
                businessId,
                businessOrderId,
                expectedVersion);
    }

    public void insertHistory(
            String id,
            GroupState group,
            long version,
            String actorUserId,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO business_order_status_history (
                            id, business_order_id, business_id, from_status, to_status,
                            business_order_version, reason_code, actor_user_id,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, ?, ?, 'PENDING_ACCEPTANCE', 'ACCEPTED', ?,
                                  'BUSINESS_ACCEPTED', ?, ?, ?, ?)
                        """,
                id,
                group.businessOrderId(),
                group.businessId(),
                version,
                actorUserId,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    public void insertOutbox(
            String eventId,
            String businessOrderId,
            String payloadJson,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            payload_json, correlation_id, causation_id, created_at
                        ) VALUES (?, 'BUSINESS_ORDER', ?, 'business_order.accepted', 1,
                                  CAST(? AS JSON), ?, ?, ?)
                        """,
                eventId,
                businessOrderId,
                payloadJson,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    public void completeCommand(
            String commandId,
            long version,
            Instant resultUpdatedAt,
            Instant now) {
        jdbc.update("""
                        UPDATE business_order_acceptance_commands
                        SET state = 'COMPLETED', result_version = ?,
                            result_updated_at = ?, updated_at = ?
                        WHERE id = ? AND state = 'IN_PROGRESS'
                        """,
                version,
                Timestamp.from(resultUpdatedAt),
                Timestamp.from(now),
                commandId);
    }

    public record GroupState(
            String businessOrderId,
            String orderId,
            String businessId,
            String fulfillmentStatus,
            String cancellationStatus,
            long version,
            String paymentStatus,
            Instant updatedAt
    ) {
    }
}
