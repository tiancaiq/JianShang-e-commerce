package com.msb.ecom.order_service.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderCancellationRepository {

    private final JdbcTemplate jdbc;

    public OrderCancellationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Locks the buyer-owned aggregate before evaluating or changing cancellation state.
    public Optional<OrderRecord> lockOwnedOrder(String orderId, String buyerId) {
        return jdbc.query("""
                        SELECT id, checkout_id, buyer_id, status, payment_status, version
                        FROM orders
                        WHERE id = ? AND buyer_id = ?
                        FOR UPDATE
                        """,
                OrderCancellationRepository::mapOrder,
                orderId,
                buyerId).stream().findFirst();
    }

    // Serializes cancellation idempotency keys for one buyer on an existing Order-owned row.
    public boolean lockBuyerCommandScope(String buyerId) {
        return !jdbc.query("""
                        SELECT id
                        FROM orders
                        WHERE buyer_id = ?
                        ORDER BY created_at, id
                        LIMIT 1
                        FOR UPDATE
                        """,
                (rs, rowNum) -> rs.getString("id"),
                buyerId).isEmpty();
    }

    // Locks every business group so the whole-order transition is all-or-nothing.
    public List<GroupRecord> lockGroups(String orderId) {
        return jdbc.query("""
                        SELECT id, business_id, fulfillment_status, cancellation_status,
                               cancellation_cutoff_at
                        FROM business_orders
                        WHERE order_id = ?
                        ORDER BY id
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new GroupRecord(
                        rs.getString("id"),
                        rs.getString("business_id"),
                        rs.getString("fulfillment_status"),
                        rs.getString("cancellation_status"),
                        instantOrNull(rs, "cancellation_cutoff_at")),
                orderId);
    }

    // Resolves structured policy evidence from immutable checkout and order snapshots.
    public Optional<PolicyEvidence> findPolicyEvidence(
            String checkoutId,
            String businessOrderId,
            String businessId) {
        return jdbc.query("""
                        SELECT policy.id,
                               policy.version_code,
                               policy.paid_order_cancellation_mode,
                               COUNT(item.id) AS item_count,
                               SUM(CASE
                                   WHEN item.policy_version = policy.version_code THEN 0
                                   ELSE 1
                               END) AS mismatch_count
                        FROM checkout_policy_snapshots policy
                        LEFT JOIN order_items item
                          ON item.business_order_id = ?
                        WHERE policy.checkout_id = ? AND policy.business_id = ?
                        GROUP BY policy.id, policy.version_code,
                                 policy.paid_order_cancellation_mode
                        """,
                (rs, rowNum) -> new PolicyEvidence(
                        rs.getString("id"),
                        rs.getString("version_code"),
                        rs.getString("paid_order_cancellation_mode"),
                        rs.getInt("item_count"),
                        rs.getInt("mismatch_count")),
                businessOrderId,
                checkoutId,
                businessId).stream().findFirst();
    }

    public Optional<CommandRecord> findCommand(
            String buyerId,
            String operation,
            String idempotencyKey) {
        return command("""
                SELECT * FROM order_cancellation_commands
                WHERE buyer_id = ? AND operation = ? AND idempotency_key = ?
                """, buyerId, operation, idempotencyKey);
    }

    public Optional<CommandRecord> lockCommand(
            String buyerId,
            String operation,
            String idempotencyKey) {
        return command("""
                SELECT * FROM order_cancellation_commands
                WHERE buyer_id = ? AND operation = ? AND idempotency_key = ?
                FOR UPDATE
                """, buyerId, operation, idempotencyKey);
    }

    public Optional<RequestRecord> findRequest(String orderId) {
        return jdbc.query("""
                        SELECT id, order_id, status, resulting_order_version, requested_at
                        FROM order_cancellation_requests
                        WHERE order_id = ?
                        """,
                (rs, rowNum) -> new RequestRecord(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getString("status"),
                        rs.getLong("resulting_order_version"),
                        rs.getTimestamp("requested_at").toInstant()),
                orderId).stream().findFirst();
    }

    // Removes only the matching expired key so a new request cannot replay stale state.
    public int deleteExpiredCommand(
            String buyerId,
            String operation,
            String idempotencyKey,
            Instant now) {
        return jdbc.update("""
                        DELETE FROM order_cancellation_commands
                        WHERE buyer_id = ? AND operation = ? AND idempotency_key = ?
                          AND expires_at <= ?
                        """,
                buyerId,
                operation,
                idempotencyKey,
                Timestamp.from(now));
    }

    // Claims the durable key; a concurrent winner is resolved by the service.
    public boolean insertCommand(
            String id,
            String buyerId,
            String orderId,
            String operation,
            String idempotencyKey,
            String requestHash,
            long expectedVersion,
            Instant now,
            Instant expiresAt) {
        try {
            jdbc.update("""
                            INSERT INTO order_cancellation_commands (
                                id, buyer_id, order_id, operation, idempotency_key, request_hash,
                                state, expected_order_version, created_at, updated_at, expires_at
                            ) VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
                            """,
                    id,
                    buyerId,
                    orderId,
                    operation,
                    idempotencyKey,
                    requestHash,
                    expectedVersion,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(expiresAt));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public void completeCommand(
            String commandId,
            String requestId,
            String responseJson,
            String responseEtag,
            Instant now) {
        int changed = jdbc.update("""
                        UPDATE order_cancellation_commands
                        SET state = 'COMPLETED',
                            cancellation_request_id = ?,
                            http_status = 200,
                            response_json = CAST(? AS JSON),
                            response_etag = ?,
                            updated_at = ?
                        WHERE id = ? AND state = 'IN_PROGRESS'
                        """,
                requestId,
                responseJson,
                responseEtag,
                Timestamp.from(now),
                commandId);
        requireOne(changed, "Cancellation command completion failed.");
    }

    public int transitionOrder(
            String orderId,
            String buyerId,
            long expectedVersion,
            Instant now) {
        return jdbc.update("""
                        UPDATE orders
                        SET status = 'CANCELLATION_REQUESTED',
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND buyer_id = ? AND version = ? AND status = 'CONFIRMED'
                        """,
                Timestamp.from(now),
                orderId,
                buyerId,
                expectedVersion);
    }

    public void transitionGroup(String businessOrderId, Instant now) {
        int changed = jdbc.update("""
                        UPDATE business_orders
                        SET cancellation_status = 'CANCELLATION_PENDING',
                            updated_at = ?
                        WHERE id = ? AND cancellation_status = 'NONE'
                        """,
                Timestamp.from(now),
                businessOrderId);
        requireOne(changed, "Business order cancellation transition failed.");
    }

    public void insertRequest(
            String id,
            String orderId,
            String buyerId,
            long expectedVersion,
            Instant requestedAt,
            String correlationId,
            String causationId) {
        jdbc.update("""
                        INSERT INTO order_cancellation_requests (
                            id, order_id, buyer_id, status, expected_order_version,
                            resulting_order_version, requested_at, correlation_id,
                            causation_id, created_at
                        ) VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                        """,
                id,
                orderId,
                buyerId,
                expectedVersion,
                expectedVersion + 1,
                Timestamp.from(requestedAt),
                correlationId,
                causationId,
                Timestamp.from(requestedAt));
    }

    public void insertGroupEvidence(
            String id,
            String requestId,
            GroupRecord group,
            PolicyEvidence policy,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_cancellation_request_groups (
                            id, cancellation_request_id, business_order_id, business_id,
                            checkout_policy_snapshot_id, policy_version_code, policy_mode,
                            original_fulfillment_status, original_cancellation_status,
                            observed_cancellation_cutoff_at, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                requestId,
                group.businessOrderId(),
                group.businessId(),
                policy.snapshotId(),
                policy.versionCode(),
                policy.mode(),
                group.fulfillmentStatus(),
                group.cancellationStatus(),
                timestampOrNull(group.cancellationCutoffAt()),
                Timestamp.from(now));
    }

    public void insertGroupHistory(
            String id,
            String requestId,
            GroupRecord group,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO business_order_cancellation_history (
                            id, business_order_id, business_id, cancellation_request_id,
                            from_status, to_status, reason_code, correlation_id,
                            causation_id, created_at
                        ) VALUES (?, ?, ?, ?, 'NONE', 'CANCELLATION_PENDING',
                            'BUYER_CANCELLATION_REQUESTED', ?, ?, ?)
                        """,
                id,
                group.businessOrderId(),
                group.businessId(),
                requestId,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    public void insertOrderHistory(
            String id,
            String orderId,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_status_history (
                            id, order_id, from_status, to_status, reason_code, actor_scope,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, ?, 'CONFIRMED', 'CANCELLATION_REQUESTED',
                            'BUYER_CANCELLATION_REQUESTED', 'BUYER', ?, ?, ?)
                        """,
                id,
                orderId,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    public void insertOutbox(
            String id,
            String orderId,
            String payloadJson,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            payload_json, correlation_id, causation_id, created_at
                        ) VALUES (?, 'ORDER', ?, 'order.cancellation_requested', 1,
                            CAST(? AS JSON), ?, ?, ?)
                        """,
                id,
                orderId,
                payloadJson,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    public int purgeExpired(Instant now, int limit) {
        return jdbc.update("""
                        DELETE FROM order_cancellation_commands
                        WHERE id IN (
                            SELECT id FROM (
                                SELECT id FROM order_cancellation_commands
                                WHERE expires_at <= ?
                                ORDER BY expires_at, id
                                LIMIT ?
                            ) expired
                        )
                        """,
                Timestamp.from(now),
                limit);
    }

    private Optional<CommandRecord> command(String sql, Object... parameters) {
        return jdbc.query(sql,
                (rs, rowNum) -> new CommandRecord(
                        rs.getString("id"),
                        rs.getString("order_id"),
                        rs.getString("request_hash"),
                        rs.getString("state"),
                        rs.getString("cancellation_request_id"),
                        rs.getString("response_json"),
                        rs.getString("response_etag")),
                parameters).stream().findFirst();
    }

    private static OrderRecord mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRecord(
                rs.getString("id"),
                rs.getString("checkout_id"),
                rs.getString("buyer_id"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getLong("version"));
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static void requireOne(int changed, String message) {
        if (changed != 1) {
            throw new EmptyResultDataAccessException(message, 1);
        }
    }

    public record OrderRecord(
            String orderId,
            String checkoutId,
            String buyerId,
            String status,
            String paymentStatus,
            long version
    ) {
    }

    public record GroupRecord(
            String businessOrderId,
            String businessId,
            String fulfillmentStatus,
            String cancellationStatus,
            Instant cancellationCutoffAt
    ) {
    }

    public record PolicyEvidence(
            String snapshotId,
            String versionCode,
            String mode,
            int itemCount,
            int mismatchCount
    ) {
    }

    public record CommandRecord(
            String id,
            String orderId,
            String requestHash,
            String state,
            String cancellationRequestId,
            String responseJson,
            String responseEtag
    ) {
    }

    public record RequestRecord(
            String requestId,
            String orderId,
            String status,
            long resultingVersion,
            Instant requestedAt
    ) {
    }
}
