package com.msb.ecom.inventory_service.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class InventoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public InventoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<InventoryItemRecord> findItem(String businessId, String listingId) {
        return jdbcTemplate.query("""
                        select id, business_id, listing_id, sku_snapshot, catalog_version_snapshot,
                               on_hand, reserved, version, initialized_at, created_at, updated_at
                        from inventory_items
                        where business_id = ? and listing_id = ?
                        """,
                (rs, rowNum) -> itemRecord(rs),
                businessId,
                listingId).stream().findFirst();
    }

    public Optional<InventoryItemRecord> findItemByListingId(String listingId) {
        return jdbcTemplate.query("""
                        select id, business_id, listing_id, sku_snapshot, catalog_version_snapshot,
                               on_hand, reserved, version, initialized_at, created_at, updated_at
                        from inventory_items
                        where listing_id = ?
                        """,
                (rs, rowNum) -> itemRecord(rs),
                listingId).stream().findFirst();
    }

    public Map<String, InventoryItemRecord> lockItemsByListingIds(Collection<String> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        List<String> sortedIds = listingIds.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(sortedIds.size(), "?"));
        List<InventoryItemRecord> rows = jdbcTemplate.query("""
                        select id, business_id, listing_id, sku_snapshot, catalog_version_snapshot,
                               on_hand, reserved, version, initialized_at, created_at, updated_at
                        from inventory_items
                        where listing_id in (%s)
                        order by listing_id
                        for update
                        """.formatted(placeholders),
                (rs, rowNum) -> itemRecord(rs),
                sortedIds.toArray());
        Map<String, InventoryItemRecord> byListing = new LinkedHashMap<>();
        rows.forEach(row -> byListing.put(row.listingId(), row));
        return byListing;
    }

    public Map<String, InventoryItemRecord> findItemsByIds(Collection<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        List<InventoryItemRecord> rows = jdbcTemplate.query("""
                        select id, business_id, listing_id, sku_snapshot, catalog_version_snapshot,
                               on_hand, reserved, version, initialized_at, created_at, updated_at
                        from inventory_items
                        where id in (%s)
                        """.formatted(placeholders),
                (rs, rowNum) -> itemRecord(rs),
                itemIds.toArray());
        Map<String, InventoryItemRecord> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        return byId;
    }

    public Map<String, InventoryItemRecord> findItems(String businessId, Collection<String> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(listingIds.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(businessId);
        parameters.addAll(listingIds);
        List<InventoryItemRecord> rows = jdbcTemplate.query("""
                        select id, business_id, listing_id, sku_snapshot, catalog_version_snapshot,
                               on_hand, reserved, version, initialized_at, created_at, updated_at
                        from inventory_items
                        where business_id = ? and listing_id in (%s)
                        """.formatted(placeholders),
                (rs, rowNum) -> itemRecord(rs),
                parameters.toArray());
        Map<String, InventoryItemRecord> byListing = new LinkedHashMap<>();
        rows.forEach(row -> byListing.put(row.listingId(), row));
        return byListing;
    }

    public void insertItem(InventoryItemRecord item) {
        jdbcTemplate.update("""
                        insert into inventory_items (
                            id, business_id, listing_id, sku_snapshot, catalog_version_snapshot,
                            on_hand, reserved, version, initialized_at, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                item.id(),
                item.businessId(),
                item.listingId(),
                item.skuSnapshot(),
                item.catalogVersionSnapshot(),
                item.onHand(),
                item.reserved(),
                item.version(),
                Timestamp.from(item.initializedAt()),
                Timestamp.from(item.createdAt()),
                Timestamp.from(item.updatedAt()));
    }

    public int updateOnHand(
            String itemId,
            String businessId,
            long expectedVersion,
            int nextOnHand,
            Instant updatedAt) {
        return jdbcTemplate.update("""
                        update inventory_items
                        set on_hand = ?, version = version + 1, updated_at = ?
                        where id = ?
                          and business_id = ?
                          and version = ?
                          and reserved <= ?
                        """,
                nextOnHand,
                Timestamp.from(updatedAt),
                itemId,
                businessId,
                expectedVersion,
                nextOnHand);
    }

    public int updateBalances(
            String itemId,
            long expectedVersion,
            int nextOnHand,
            int nextReserved,
            Instant updatedAt) {
        return jdbcTemplate.update("""
                        update inventory_items
                        set on_hand = ?, reserved = ?, version = version + 1, updated_at = ?
                        where id = ?
                          and version = ?
                          and ? >= 0
                          and ? >= 0
                          and ? <= ?
                        """,
                nextOnHand,
                nextReserved,
                Timestamp.from(updatedAt),
                itemId,
                expectedVersion,
                nextOnHand,
                nextReserved,
                nextReserved,
                nextOnHand);
    }

    public void insertMovement(
            String id,
            InventoryItemRecord item,
            String operation,
            String reason,
            int delta,
            int before,
            int after,
            String note,
            String actorUserId,
            String commandId,
            String correlationId,
            Instant createdAt) {
        jdbcTemplate.update("""
                        insert into inventory_movements (
                            id, inventory_item_id, business_id, listing_id, operation, reason_code,
                            quantity_delta, on_hand_before, on_hand_after, reserved_snapshot,
                            note, actor_user_id, command_id, correlation_id, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                item.id(),
                item.businessId(),
                item.listingId(),
                operation,
                reason,
                delta,
                before,
                after,
                item.reserved(),
                note,
                actorUserId,
                commandId,
                correlationId,
                Timestamp.from(createdAt));
    }

    public List<InventoryMovementRecord> findMovements(
            String inventoryItemId,
            Instant cursorCreatedAt,
            String cursorId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, operation, reason_code, quantity_delta, on_hand_before, on_hand_after,
                       reserved_snapshot, note, created_at
                from inventory_movements
                where inventory_item_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(inventoryItemId);
        if (cursorCreatedAt != null && cursorId != null) {
            sql.append("""
                     and (created_at < ? or (created_at = ? and id < ?))
                    """);
            parameters.add(Timestamp.from(cursorCreatedAt));
            parameters.add(Timestamp.from(cursorCreatedAt));
            parameters.add(cursorId);
        }
        sql.append(" order by created_at desc, id desc limit ?");
        parameters.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new InventoryMovementRecord(
                rs.getString("id"),
                rs.getString("operation"),
                rs.getString("reason_code"),
                rs.getInt("quantity_delta"),
                rs.getInt("on_hand_before"),
                rs.getInt("on_hand_after"),
                rs.getInt("reserved_snapshot"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant()), parameters.toArray());
    }

    public Optional<IdempotencyRecord> findIdempotency(String callerScope, String key) {
        return jdbcTemplate.query("""
                        select request_hash, http_status, response_json
                        from inventory_idempotency_records
                        where caller_scope = ? and idempotency_key = ?
                        """,
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_hash"),
                        rs.getInt("http_status"),
                        rs.getString("response_json")),
                callerScope,
                key).stream().findFirst();
    }

    public void insertIdempotency(
            String id,
            String callerScope,
            String key,
            String requestHash,
            String operation,
            String resultResourceId,
            int httpStatus,
            String responseJson,
            Instant createdAt) {
        jdbcTemplate.update("""
                        insert into inventory_idempotency_records (
                            id, caller_scope, idempotency_key, request_hash, operation,
                            result_resource_id, http_status, response_json, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, cast(? as json), ?)
                        """,
                id,
                callerScope,
                key,
                requestHash,
                operation,
                resultResourceId,
                httpStatus,
                responseJson,
                Timestamp.from(createdAt));
    }

    public void insertOutbox(
            String id,
            String aggregateId,
            String eventType,
            String payload,
            String correlationId,
            String causationId,
            Instant createdAt) {
        insertOutbox(
                id,
                "INVENTORY_ITEM",
                aggregateId,
                eventType,
                payload,
                correlationId,
                causationId,
                createdAt);
    }

    public void insertOutbox(
            String id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            String correlationId,
            String causationId,
            Instant createdAt) {
        jdbcTemplate.update("""
                        insert into inventory_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            payload, correlation_id, causation_id, created_at
                        ) values (?, ?, ?, ?, 1, cast(? as json), ?, ?, ?)
                        """,
                id,
                aggregateType,
                aggregateId,
                eventType,
                payload,
                correlationId,
                causationId,
                Timestamp.from(createdAt));
    }

    private InventoryItemRecord itemRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryItemRecord(
                rs.getString("id"),
                rs.getString("business_id"),
                rs.getString("listing_id"),
                rs.getString("sku_snapshot"),
                rs.getLong("catalog_version_snapshot"),
                rs.getInt("on_hand"),
                rs.getInt("reserved"),
                rs.getLong("version"),
                rs.getTimestamp("initialized_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
