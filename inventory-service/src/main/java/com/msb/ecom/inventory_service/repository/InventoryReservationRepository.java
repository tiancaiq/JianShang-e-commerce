package com.msb.ecom.inventory_service.repository;

import com.msb.ecom.inventory_service.model.ReservationPurpose;
import com.msb.ecom.inventory_service.model.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class InventoryReservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public InventoryReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<InventoryReservationRecord> find(String reservationId) {
        return queryReservation("""
                select *
                from inventory_reservations
                where id = ?
                """, reservationId);
    }

    public Optional<InventoryReservationRecord> lock(String reservationId) {
        return queryReservation("""
                select *
                from inventory_reservations
                where id = ?
                for update
                """, reservationId);
    }

    public Optional<InventoryReservationRecord> findByCheckoutAndPurpose(
            String checkoutId,
            ReservationPurpose purpose) {
        return queryReservation("""
                select *
                from inventory_reservations
                where checkout_id = ? and purpose = ?
                """, checkoutId, purpose.name());
    }

    public void insertReservation(InventoryReservationRecord reservation) {
        jdbcTemplate.update("""
                        insert into inventory_reservations (
                            id, checkout_id, purpose, status, expires_at,
                            committed_at, released_at, release_reason, version,
                            created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reservation.id(),
                reservation.checkoutId(),
                reservation.purpose().name(),
                reservation.status().name(),
                Timestamp.from(reservation.expiresAt()),
                timestamp(reservation.committedAt()),
                timestamp(reservation.releasedAt()),
                reservation.releaseReason(),
                reservation.version(),
                Timestamp.from(reservation.createdAt()),
                Timestamp.from(reservation.updatedAt()));
    }

    public void insertItem(
            String id,
            String reservationId,
            InventoryItemRecord inventory,
            int quantity,
            int reservedAfter,
            long versionAfter,
            Instant now) {
        jdbcTemplate.update("""
                        insert into inventory_reservation_items (
                            id, reservation_id, inventory_item_id, business_id, listing_id,
                            quantity, on_hand_after_reserve, reserved_after_reserve,
                            item_version_after_reserve, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                reservationId,
                inventory.id(),
                inventory.businessId(),
                inventory.listingId(),
                quantity,
                inventory.onHand(),
                reservedAfter,
                versionAfter,
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public List<InventoryReservationItemRecord> findItems(String reservationId) {
        return jdbcTemplate.query("""
                        select id, reservation_id, inventory_item_id, business_id, listing_id,
                               quantity, created_at, updated_at
                        from inventory_reservation_items
                        where reservation_id = ?
                        order by listing_id
                        """,
                (rs, rowNum) -> new InventoryReservationItemRecord(
                        rs.getString("id"),
                        rs.getString("reservation_id"),
                        rs.getString("inventory_item_id"),
                        rs.getString("business_id"),
                        rs.getString("listing_id"),
                        rs.getInt("quantity"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                reservationId);
    }

    public int transition(
            String reservationId,
            ReservationStatus nextStatus,
            String releaseReason,
            Instant now) {
        return jdbcTemplate.update("""
                        update inventory_reservations
                        set status = ?,
                            committed_at = ?,
                            released_at = ?,
                            release_reason = ?,
                            version = version + 1,
                            updated_at = ?
                        where id = ? and status = 'ACTIVE'
                        """,
                nextStatus.name(),
                nextStatus == ReservationStatus.COMMITTED ? Timestamp.from(now) : null,
                nextStatus == ReservationStatus.RELEASED || nextStatus == ReservationStatus.EXPIRED
                        ? Timestamp.from(now)
                        : null,
                releaseReason,
                Timestamp.from(now),
                reservationId);
    }

    public void updateItemTerminalSnapshot(
            String reservationItemId,
            int onHand,
            int reserved,
            long itemVersion,
            Instant now) {
        jdbcTemplate.update("""
                        update inventory_reservation_items
                        set on_hand_after_terminal = ?,
                            reserved_after_terminal = ?,
                            item_version_after_terminal = ?,
                            updated_at = ?
                        where id = ? and item_version_after_terminal is null
                        """,
                onHand,
                reserved,
                itemVersion,
                Timestamp.from(now),
                reservationItemId);
    }

    public void insertHistory(
            String id,
            String reservationId,
            String checkoutId,
            String eventType,
            String fromStatus,
            String toStatus,
            String reason,
            String commandId,
            String deduplicationKey,
            String correlationId,
            Instant now) {
        jdbcTemplate.update("""
                        insert into inventory_reservation_history (
                            id, reservation_id, checkout_id, event_type, from_status, to_status,
                            reason, command_id, deduplication_key, correlation_id, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                reservationId,
                checkoutId,
                eventType,
                fromStatus,
                toStatus,
                reason,
                commandId,
                deduplicationKey,
                correlationId,
                Timestamp.from(now));
    }

    public boolean hasHistoryDeduplicationKey(String deduplicationKey) {
        Integer count = jdbcTemplate.queryForObject("""
                        select count(*)
                        from inventory_reservation_history
                        where deduplication_key = ?
                        """,
                Integer.class,
                deduplicationKey);
        return count != null && count > 0;
    }

    public List<String> lockExpiredReservationIds(Instant now, int limit) {
        return jdbcTemplate.query("""
                        select id
                        from inventory_reservations
                        where status = 'ACTIVE' and expires_at <= ?
                        order by expires_at, id
                        limit ?
                        for update skip locked
                        """,
                (rs, rowNum) -> rs.getString("id"),
                Timestamp.from(now),
                limit);
    }

    private Optional<InventoryReservationRecord> queryReservation(String sql, Object... parameters) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> reservation(rs), parameters)
                .stream()
                .findFirst();
    }

    private InventoryReservationRecord reservation(ResultSet rs) throws SQLException {
        return new InventoryReservationRecord(
                rs.getString("id"),
                rs.getString("checkout_id"),
                ReservationPurpose.valueOf(rs.getString("purpose")),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("expires_at").toInstant(),
                instant(rs.getTimestamp("committed_at")),
                instant(rs.getTimestamp("released_at")),
                rs.getString("release_reason"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
