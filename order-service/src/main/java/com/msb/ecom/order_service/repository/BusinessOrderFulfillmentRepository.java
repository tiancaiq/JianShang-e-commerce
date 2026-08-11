package com.msb.ecom.order_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class BusinessOrderFulfillmentRepository {

    private final JdbcTemplate jdbc;

    public BusinessOrderFulfillmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int purgeExpired(Instant now, int limit) {
        return jdbc.update("""
                DELETE FROM business_order_fulfillment_commands
                WHERE expires_at < ?
                ORDER BY expires_at, id
                LIMIT ?
                """, Timestamp.from(now), limit);
    }

    public Optional<Command> lockCommand(
            String actorUserId,
            String businessId,
            String operation,
            String idempotencyKey) {
        return jdbc.query("""
                        SELECT id, request_hash, state, business_order_id,
                               result_version, result_updated_at, result_shipment_id
                        FROM business_order_fulfillment_commands
                        WHERE actor_user_id = ? AND business_id = ?
                          AND operation = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new Command(
                        rs.getString("id"),
                        rs.getString("request_hash"),
                        rs.getString("state"),
                        rs.getString("business_order_id"),
                        (Long) rs.getObject("result_version"),
                        instant(rs.getTimestamp("result_updated_at")),
                        rs.getString("result_shipment_id")),
                actorUserId, businessId, operation, idempotencyKey).stream().findFirst();
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
                        INSERT INTO business_order_fulfillment_commands (
                            id, actor_user_id, business_id, operation, idempotency_key,
                            request_hash, state, business_order_id, created_at, updated_at,
                            expires_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
                        """,
                id, actorUserId, businessId, operation, idempotencyKey,
                requestHash, businessOrderId, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(expiresAt));
    }

    public Optional<GroupState> lockOwnedGroup(String businessId, String businessOrderId) {
        return jdbc.query("""
                        SELECT bo.id, bo.order_id, bo.business_id, bo.fulfillment_status,
                               bo.cancellation_status, bo.version, bo.created_at, bo.updated_at,
                               o.payment_status
                        FROM business_orders bo
                        JOIN orders o ON o.id = bo.order_id
                        WHERE bo.business_id = ? AND bo.id = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new GroupState(
                        rs.getString("id"), rs.getString("order_id"),
                        rs.getString("business_id"), rs.getString("fulfillment_status"),
                        rs.getString("cancellation_status"), rs.getLong("version"),
                        rs.getString("payment_status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                businessId, businessOrderId).stream().findFirst();
    }

    public int transitionGroup(
            String businessId,
            String businessOrderId,
            long expectedVersion,
            String fromStatus,
            String toStatus,
            Instant now) {
        return jdbc.update("""
                        UPDATE business_orders
                        SET fulfillment_status = ?, version = version + 1, updated_at = ?
                        WHERE business_id = ? AND id = ? AND version = ?
                          AND fulfillment_status = ? AND cancellation_status = 'NONE'
                        """,
                toStatus, Timestamp.from(now), businessId, businessOrderId,
                expectedVersion, fromStatus);
    }

    public void insertGroupHistory(
            String id,
            GroupState group,
            String toStatus,
            long version,
            String reasonCode,
            String actorUserId,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO business_order_status_history (
                            id, business_order_id, business_id, from_status, to_status,
                            business_order_version, reason_code, actor_user_id,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, group.businessOrderId(), group.businessId(), group.fulfillmentStatus(),
                toStatus, version, reasonCode, actorUserId, correlationId, causationId,
                Timestamp.from(now));
    }

    public void insertShipment(
            String shipmentId,
            GroupState group,
            String carrier,
            String service,
            String tracking,
            Instant shippedAt,
            Instant now) {
        jdbc.update("""
                        INSERT INTO shipments (
                            id, business_order_id, business_id, source, carrier_display_name,
                            service_display_name, tracking_number, status, version, shipped_at,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 'LOCAL_DEMO_MANUAL', ?, ?, ?, 'SHIPPED', 0, ?, ?, ?)
                        """,
                shipmentId, group.businessOrderId(), group.businessId(), carrier, service,
                tracking, Timestamp.from(shippedAt), Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<Shipment> findOwnedShipment(String businessId, String businessOrderId) {
        return jdbc.query("""
                        SELECT id, business_order_id, business_id, source,
                               carrier_display_name, service_display_name, tracking_number,
                               status, version, shipped_at, delivered_at, created_at, updated_at
                        FROM shipments
                        WHERE business_id = ? AND business_order_id = ?
                        """, BusinessOrderFulfillmentRepository::mapShipment,
                businessId, businessOrderId).stream().findFirst();
    }

    public int deliverShipment(
            String shipmentId,
            String businessId,
            String businessOrderId,
            Instant deliveredAt) {
        return jdbc.update("""
                        UPDATE shipments
                        SET status = 'DELIVERED', version = version + 1,
                            delivered_at = ?, updated_at = ?
                        WHERE id = ? AND business_id = ? AND business_order_id = ?
                          AND status = 'SHIPPED' AND version = 0
                        """,
                Timestamp.from(deliveredAt), Timestamp.from(deliveredAt), shipmentId,
                businessId, businessOrderId);
    }

    public void insertShipmentHistory(
            String id,
            String shipmentId,
            GroupState group,
            String fromStatus,
            String toStatus,
            long version,
            String reasonCode,
            String actorUserId,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO shipment_status_history (
                            id, shipment_id, business_order_id, business_id, from_status,
                            to_status, shipment_version, reason_code, actor_user_id,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, shipmentId, group.businessOrderId(), group.businessId(), fromStatus,
                toStatus, version, reasonCode, actorUserId, correlationId, causationId,
                Timestamp.from(now));
    }

    public void insertOutbox(
            String eventId,
            String businessOrderId,
            String eventType,
            String payloadJson,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            payload_json, correlation_id, causation_id, created_at
                        ) VALUES (?, 'BUSINESS_ORDER', ?, ?, 1, CAST(? AS JSON), ?, ?, ?)
                        """,
                eventId, businessOrderId, eventType, payloadJson, correlationId,
                causationId, Timestamp.from(now));
    }

    public void completeCommand(
            String commandId,
            long version,
            Instant resultUpdatedAt,
            String shipmentId,
            Instant now) {
        jdbc.update("""
                        UPDATE business_order_fulfillment_commands
                        SET state = 'COMPLETED', result_version = ?, result_updated_at = ?,
                            result_shipment_id = ?, updated_at = ?
                        WHERE id = ? AND state = 'IN_PROGRESS'
                        """,
                version, Timestamp.from(resultUpdatedAt), shipmentId, Timestamp.from(now),
                commandId);
    }

    public List<TimelineEntry> findGroupTimeline(String businessOrderId) {
        return jdbc.query("""
                        SELECT to_status, created_at
                        FROM business_order_status_history
                        WHERE business_order_id = ?
                        ORDER BY business_order_version
                        """,
                (rs, rowNum) -> new TimelineEntry(
                        rs.getString("to_status"), rs.getTimestamp("created_at").toInstant()),
                businessOrderId);
    }

    private static Shipment mapShipment(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new Shipment(
                rs.getString("id"), rs.getString("business_order_id"),
                rs.getString("business_id"), rs.getString("source"),
                rs.getString("carrier_display_name"), rs.getString("service_display_name"),
                rs.getString("tracking_number"), rs.getString("status"),
                rs.getLong("version"), rs.getTimestamp("shipped_at").toInstant(),
                instant(rs.getTimestamp("delivered_at")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Command(
            String id, String requestHash, String state, String businessOrderId,
            Long resultVersion, Instant resultUpdatedAt, String resultShipmentId) {}

    public record GroupState(
            String businessOrderId, String orderId, String businessId,
            String fulfillmentStatus, String cancellationStatus, long version,
            String paymentStatus, Instant createdAt, Instant updatedAt) {}

    public record Shipment(
            String shipmentId, String businessOrderId, String businessId, String source,
            String carrierDisplayName, String serviceDisplayName, String trackingNumber,
            String status, long version, Instant shippedAt, Instant deliveredAt,
            Instant createdAt, Instant updatedAt) {}

    public record TimelineEntry(String status, Instant occurredAt) {}
}
