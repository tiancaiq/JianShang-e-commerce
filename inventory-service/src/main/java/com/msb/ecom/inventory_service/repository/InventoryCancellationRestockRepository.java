package com.msb.ecom.inventory_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class InventoryCancellationRestockRepository {

    private final JdbcTemplate jdbc;

    public InventoryCancellationRestockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RestockRecord> findByRequest(String cancellationRequestId) {
        return query("""
                SELECT * FROM inventory_cancellation_restocks
                WHERE cancellation_request_id = ?
                """, cancellationRequestId);
    }

    public Optional<RestockRecord> findByKey(String idempotencyKey) {
        return query("""
                SELECT * FROM inventory_cancellation_restocks
                WHERE idempotency_key = ?
                """, idempotencyKey);
    }

    public List<InventoryReservationItemRecord> reservationItems(String reservationId) {
        return jdbc.query("""
                        SELECT id, reservation_id, inventory_item_id, business_id, listing_id,
                               quantity, created_at, updated_at
                        FROM inventory_reservation_items
                        WHERE reservation_id = ?
                        ORDER BY listing_id
                        """,
                (rs, rowNum) -> new InventoryReservationItemRecord(
                        rs.getString("id"), rs.getString("reservation_id"),
                        rs.getString("inventory_item_id"), rs.getString("business_id"),
                        rs.getString("listing_id"), rs.getInt("quantity"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                reservationId);
    }

    public void insert(
            String id,
            String cancellationRequestId,
            String orderId,
            String reservationId,
            String idempotencyKey,
            int restoredQuantity,
            String correlationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO inventory_cancellation_restocks (
                            id, cancellation_request_id, order_id, reservation_id,
                            idempotency_key, status, restored_quantity, correlation_id,
                            created_at, completed_at
                        ) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?)
                        """,
                id, cancellationRequestId, orderId, reservationId, idempotencyKey,
                restoredQuantity, correlationId, Timestamp.from(now), Timestamp.from(now));
    }

    private Optional<RestockRecord> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> new RestockRecord(
                        rs.getString("id"), rs.getString("cancellation_request_id"),
                        rs.getString("order_id"), rs.getString("reservation_id"),
                        rs.getString("idempotency_key"), rs.getString("status"),
                        rs.getInt("restored_quantity"),
                        rs.getTimestamp("completed_at").toInstant()), args)
                .stream().findFirst();
    }

    public record RestockRecord(
            String id,
            String cancellationRequestId,
            String orderId,
            String reservationId,
            String idempotencyKey,
            String status,
            int restoredQuantity,
            Instant completedAt
    ) {
    }
}
