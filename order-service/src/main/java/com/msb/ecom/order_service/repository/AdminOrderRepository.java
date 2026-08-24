package com.msb.ecom.order_service.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class AdminOrderRepository {
    private static final String FULFILLMENT_STATUS_SQL = """
            CASE
              WHEN EXISTS (SELECT 1 FROM business_orders bo WHERE bo.order_id = o.id
                           AND bo.cancellation_status = 'CANCELLED') THEN 'CANCELLED'
              WHEN EXISTS (SELECT 1 FROM business_orders bo WHERE bo.order_id = o.id
                           AND bo.cancellation_status = 'CANCELLATION_PENDING') THEN 'CANCELLATION_PENDING'
              WHEN EXISTS (SELECT 1 FROM business_orders bo WHERE bo.order_id = o.id
                           AND bo.fulfillment_status = 'PENDING_ACCEPTANCE') THEN 'PENDING_ACCEPTANCE'
              WHEN EXISTS (SELECT 1 FROM business_orders bo WHERE bo.order_id = o.id
                           AND bo.fulfillment_status = 'ACCEPTED') THEN 'ACCEPTED'
              WHEN EXISTS (SELECT 1 FROM business_orders bo WHERE bo.order_id = o.id
                           AND bo.fulfillment_status = 'PROCESSING') THEN 'PROCESSING'
              WHEN EXISTS (SELECT 1 FROM business_orders bo WHERE bo.order_id = o.id
                           AND bo.fulfillment_status = 'SHIPPED') THEN 'SHIPPED'
              ELSE 'DELIVERED'
            END
            """;

    private final JdbcTemplate jdbc;

    public AdminOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SearchResult search(SearchFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendFilters(where, args, filter);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM orders o" + where, Long.class, args.toArray());
        String sql = """
                SELECT o.id, o.buyer_id, o.status, o.payment_status, o.total, o.currency,
                       o.created_at, o.updated_at, o.version,
                       (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.id) item_count,
                       (SELECT GROUP_CONCAT(bo.business_id ORDER BY bo.business_id SEPARATOR ',')
                          FROM business_orders bo WHERE bo.order_id = o.id) business_ids,
                       %s fulfillment_status
                FROM orders o
                """.formatted(FULFILLMENT_STATUS_SQL)
                + where + " ORDER BY " + sortExpression(filter.sort()) + " LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.size());
        pageArgs.add(filter.page() * filter.size());
        List<SearchRow> rows = jdbc.query(sql, AdminOrderRepository::searchRow, pageArgs.toArray());
        return new SearchResult(rows, total == null ? 0 : total);
    }

    public Optional<DetailRow> detail(String orderId) {
        return jdbc.query("""
                        SELECT o.id, o.order_number, o.checkout_id, o.payment_intent_id, o.buyer_id,
                               o.status, o.payment_status, o.currency, o.subtotal, o.shipping, o.tax,
                               o.discount, o.total, o.version, o.created_at, o.updated_at,
                               c.status checkout_status, c.version checkout_version, c.cart_version,
                               c.cart_snapshot_hash, c.expires_at checkout_expires_at,
                               c.reservation_id, c.reservation_status, c.reservation_version,
                               c.release_status
                        FROM orders o
                        JOIN checkout_sessions c ON c.id = o.checkout_id
                        WHERE o.id = ?
                        """,
                (rs, n) -> new DetailRow(
                        rs.getString("id"), rs.getString("order_number"), rs.getString("checkout_id"),
                        rs.getString("payment_intent_id"), rs.getString("buyer_id"), rs.getString("status"),
                        rs.getString("payment_status"), rs.getString("currency"), rs.getBigDecimal("subtotal"),
                        rs.getBigDecimal("shipping"), rs.getBigDecimal("tax"), rs.getBigDecimal("discount"),
                        rs.getBigDecimal("total"), rs.getLong("version"), instant(rs, "created_at"),
                        instant(rs, "updated_at"), rs.getString("checkout_status"),
                        rs.getLong("checkout_version"), rs.getLong("cart_version"),
                        rs.getString("cart_snapshot_hash"), instant(rs, "checkout_expires_at"),
                        rs.getString("reservation_id"), rs.getString("reservation_status"),
                        (Long) rs.getObject("reservation_version"), rs.getString("release_status")),
                orderId).stream().findFirst();
    }

    public List<GroupRow> groups(String orderId) {
        return jdbc.query("""
                        SELECT id, business_id, store_id, store_name, fulfillment_status,
                               cancellation_status, version, cancellation_cutoff_at
                        FROM business_orders WHERE order_id = ? ORDER BY created_at, id
                        """,
                (rs, n) -> new GroupRow(rs.getString("id"), rs.getString("business_id"),
                        rs.getString("store_id"), rs.getString("store_name"),
                        rs.getString("fulfillment_status"), rs.getString("cancellation_status"),
                        rs.getLong("version"), nullableInstant(rs, "cancellation_cutoff_at")), orderId);
    }

    public List<GroupRow> lockGroups(String orderId) {
        return jdbc.query("""
                        SELECT id, business_id, store_id, store_name, fulfillment_status,
                               cancellation_status, version, cancellation_cutoff_at
                        FROM business_orders WHERE order_id = ? ORDER BY id FOR UPDATE
                        """,
                (rs, n) -> new GroupRow(rs.getString("id"), rs.getString("business_id"),
                        rs.getString("store_id"), rs.getString("store_name"),
                        rs.getString("fulfillment_status"), rs.getString("cancellation_status"),
                        rs.getLong("version"), nullableInstant(rs, "cancellation_cutoff_at")), orderId);
    }

    public List<ItemRow> items(String orderId) {
        return jdbc.query("""
                        SELECT id, business_order_id, line_number, listing_id, business_id, store_id,
                               catalog_version, title, sku, item_condition, thumbnail_url, quantity,
                               unit_price, currency, line_subtotal, shipping_allocation, tax_allocation,
                               discount_allocation, line_total, policy_version
                        FROM order_items WHERE order_id = ? ORDER BY line_number, id
                        """,
                (rs, n) -> new ItemRow(rs.getString("id"), rs.getString("business_order_id"),
                        rs.getInt("line_number"), rs.getString("listing_id"), rs.getString("business_id"),
                        rs.getString("store_id"), rs.getLong("catalog_version"), rs.getString("title"),
                        rs.getString("sku"), rs.getString("item_condition"), rs.getString("thumbnail_url"),
                        rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getString("currency"),
                        rs.getBigDecimal("line_subtotal"), rs.getBigDecimal("shipping_allocation"),
                        rs.getBigDecimal("tax_allocation"), rs.getBigDecimal("discount_allocation"),
                        rs.getBigDecimal("line_total"), rs.getString("policy_version")), orderId);
    }

    public AddressRow address(String orderId) {
        return jdbc.query("""
                        SELECT label, recipient_name, phone, line1, line2, city, region, postal_code, country_code
                        FROM order_addresses WHERE order_id = ? AND address_type = 'SHIPPING'
                        """,
                (rs, n) -> new AddressRow(rs.getString("label"), rs.getString("recipient_name"),
                        rs.getString("phone"), rs.getString("line1"), rs.getString("line2"),
                        rs.getString("city"), rs.getString("region"), rs.getString("postal_code"),
                        rs.getString("country_code")), orderId).stream().findFirst().orElse(null);
    }

    public List<PolicyRow> policies(String checkoutId) {
        return jdbc.query("""
                        SELECT business_id, version_code, paid_order_cancellation_mode,
                               cancellation_text, shipping_text, return_text
                        FROM checkout_policy_snapshots WHERE checkout_id = ? ORDER BY business_id
                        """,
                (rs, n) -> new PolicyRow(rs.getString("business_id"), rs.getString("version_code"),
                        rs.getString("paid_order_cancellation_mode"), rs.getString("cancellation_text"),
                        rs.getString("shipping_text"), rs.getString("return_text")), checkoutId);
    }

    public CancellationRow cancellation(String orderId) {
        return jdbc.query("""
                        SELECT r.id, r.status, r.request_actor_type, r.request_actor_id,
                               r.admin_reason_code, r.admin_reason, r.requested_at, r.decided_at,
                               r.completed_at, c.inventory_status, c.refund_status, c.refund_id,
                               c.refund_reference, c.amount, c.currency
                        FROM order_cancellation_requests r
                        LEFT JOIN order_cancellation_compensations c ON c.cancellation_request_id = r.id
                        WHERE r.order_id = ?
                        """,
                (rs, n) -> new CancellationRow(rs.getString("id"), rs.getString("status"),
                        rs.getString("request_actor_type"), rs.getString("request_actor_id"),
                        rs.getString("admin_reason_code"), rs.getString("admin_reason"),
                        instant(rs, "requested_at"), nullableInstant(rs, "decided_at"),
                        nullableInstant(rs, "completed_at"), rs.getString("inventory_status"),
                        rs.getString("refund_status"), rs.getString("refund_id"),
                        rs.getString("refund_reference"), rs.getBigDecimal("amount"),
                        rs.getString("currency")), orderId).stream().findFirst().orElse(null);
    }

    public List<RefundRow> refunds(String orderId) {
        List<RefundRow> result = new ArrayList<>();
        CancellationRow cancellation = cancellation(orderId);
        if (cancellation != null && cancellation.refundStatus() != null) {
            result.add(new RefundRow("ORDER_CANCELLATION", cancellation.refundStatus(),
                    cancellation.refundId(), cancellation.refundReference(), cancellation.refundAmount(),
                    cancellation.refundCurrency(), cancellation.completedAt()));
        }
        result.addAll(jdbc.query("""
                        SELECT refund_status, refund_id, refund_amount, currency, completed_at
                        FROM business_order_returns WHERE order_id = ? ORDER BY created_at, id
                        """,
                (rs, n) -> new RefundRow("BUSINESS_RETURN", rs.getString("refund_status"),
                        rs.getString("refund_id"), null, rs.getBigDecimal("refund_amount"),
                        rs.getString("currency"), nullableInstant(rs, "completed_at")), orderId));
        return result;
    }

    public List<TimelineRow> timeline(String orderId) {
        List<TimelineRow> rows = new ArrayList<>();
        rows.addAll(jdbc.query("""
                        SELECT id event_id, created_at occurred_at, CONCAT('ORDER_', to_status) event_type,
                               actor_scope actor_type, NULL actor_id, NULL actor_display_name,
                               from_status previous_state, to_status new_state, reason_code reason,
                               correlation_id, causation_id request_id
                        FROM order_status_history WHERE order_id = ?
                        """, AdminOrderRepository::timelineRow, orderId));
        rows.addAll(jdbc.query("""
                        SELECT h.id event_id, h.created_at occurred_at,
                               CONCAT('FULFILLMENT_', h.to_status) event_type,
                               CASE WHEN h.actor_user_id IS NULL THEN 'SYSTEM' ELSE 'BUSINESS_USER' END actor_type,
                               h.actor_user_id actor_id, NULL actor_display_name, h.from_status previous_state,
                               h.to_status new_state, h.reason_code reason, h.correlation_id,
                               h.causation_id request_id
                        FROM business_order_status_history h
                        JOIN business_orders bo ON bo.id = h.business_order_id WHERE bo.order_id = ?
                        """, AdminOrderRepository::timelineRow, orderId));
        rows.addAll(jdbc.query("""
                        SELECT h.id event_id, h.created_at occurred_at,
                               CONCAT('SHIPMENT_', h.to_status) event_type,
                               CASE WHEN h.actor_user_id IS NULL THEN 'SYSTEM' ELSE 'BUSINESS_USER' END actor_type,
                               h.actor_user_id actor_id, NULL actor_display_name, h.from_status previous_state,
                               h.to_status new_state, h.reason_code reason, h.correlation_id,
                               h.causation_id request_id
                        FROM shipment_status_history h
                        JOIN business_orders bo ON bo.id = h.business_order_id WHERE bo.order_id = ?
                        """, AdminOrderRepository::timelineRow, orderId));
        rows.addAll(jdbc.query("""
                        SELECT e.event_id, e.occurred_at, e.event_type, 'PLATFORM_ADMIN' actor_type,
                               e.actor_admin_id actor_id, e.actor_display_name, e.previous_state,
                               e.new_state, CONCAT(e.reason_code, ': ', COALESCE(e.reason, '')) reason,
                               e.correlation_id, e.request_id
                        FROM order_admin_events e WHERE e.order_id = ?
                        """, AdminOrderRepository::timelineRow, orderId));
        rows.addAll(jdbc.query("""
                        SELECT h.id event_id, h.occurred_at, CONCAT('RETURN_', h.to_status) event_type,
                               CASE WHEN h.actor_user_id IS NULL THEN 'SYSTEM' ELSE 'MARKETPLACE_USER' END actor_type,
                               h.actor_user_id actor_id, NULL actor_display_name, h.from_status previous_state,
                               h.to_status new_state, h.reason_code reason, h.correlation_id,
                               h.causation_id request_id
                        FROM business_order_return_history h
                        JOIN business_order_returns r ON r.id = h.return_id WHERE r.order_id = ?
                        """, AdminOrderRepository::timelineRow, orderId));
        rows.addAll(jdbc.query("""
                        SELECT e.event_id, e.occurred_at, e.event_type, e.actor_type,
                               e.actor_id, e.actor_display_name, e.previous_state, e.new_state,
                               CONCAT(COALESCE(e.reason_code, ''), ': ', COALESCE(e.reason, '')) reason,
                               e.correlation_id, e.request_id
                        FROM order_dispute_events e
                        JOIN order_disputes d ON d.id = e.dispute_id WHERE d.order_id = ?
                        """, AdminOrderRepository::timelineRow, orderId));
        rows.sort(java.util.Comparator.comparing(TimelineRow::occurredAt).thenComparing(TimelineRow::eventId));
        return rows;
    }

    public Optional<LockedOrder> lockOrder(String orderId) {
        return jdbc.query("""
                        SELECT id, checkout_id, payment_intent_id, buyer_id, status, payment_status,
                               total, currency, version FROM orders WHERE id = ? FOR UPDATE
                        """,
                (rs, n) -> new LockedOrder(rs.getString("id"), rs.getString("checkout_id"),
                        rs.getString("payment_intent_id"), rs.getString("buyer_id"), rs.getString("status"),
                        rs.getString("payment_status"), rs.getBigDecimal("total"), rs.getString("currency"),
                        rs.getLong("version")), orderId).stream().findFirst();
    }

    public Optional<AdminCommand> command(String actorId, String idempotencyKey, boolean lock) {
        return jdbc.query("""
                        SELECT id, order_id, request_hash, state, cancellation_request_id, response_json
                        FROM admin_order_cancellation_commands
                        WHERE actor_admin_id = ? AND idempotency_key = ?
                        """ + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new AdminCommand(rs.getString("id"), rs.getString("order_id"),
                        rs.getString("request_hash"), rs.getString("state"),
                        rs.getString("cancellation_request_id"), rs.getString("response_json")),
                actorId, idempotencyKey).stream().findFirst();
    }

    public boolean insertCommand(String id, String actorId, String orderId, String key, String hash,
            long expectedVersion, Instant now, Instant expiresAt) {
        try {
            jdbc.update("""
                            INSERT INTO admin_order_cancellation_commands (
                                id, actor_admin_id, order_id, idempotency_key, request_hash, state,
                                expected_order_version, created_at, updated_at, expires_at
                            ) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
                            """, id, actorId, orderId, key, hash, expectedVersion,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiresAt));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public int transitionOrder(String orderId, long version, Instant now) {
        return jdbc.update("""
                        UPDATE orders SET status = 'CANCELLATION_REQUESTED', version = version + 1,
                            updated_at = ? WHERE id = ? AND version = ? AND status = 'CONFIRMED'
                        """, Timestamp.from(now), orderId, version);
    }

    public void insertCancellationRequest(String id, LockedOrder order, String actorId,
            String reasonCode, String reason, String correlationId, String requestId, Instant now) {
        jdbc.update("""
                        INSERT INTO order_cancellation_requests (
                            id, order_id, buyer_id, request_actor_type, request_actor_id,
                            admin_reason_code, admin_reason, status, expected_order_version,
                            resulting_order_version, requested_at, correlation_id, causation_id, created_at
                        ) VALUES (?, ?, ?, 'PLATFORM_ADMIN', ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)
                        """, id, order.orderId(), order.buyerId(), actorId, reasonCode, reason,
                order.version(), order.version() + 1, Timestamp.from(now), correlationId, requestId,
                Timestamp.from(now));
    }

    public void insertGroupEvidence(String id, String cancellationId, GroupRow group,
            PolicyEvidence policy, Instant now) {
        jdbc.update("""
                        INSERT INTO order_cancellation_request_groups (
                            id, cancellation_request_id, business_order_id, business_id,
                            checkout_policy_snapshot_id, policy_version_code, policy_mode,
                            original_fulfillment_status, original_cancellation_status,
                            observed_cancellation_cutoff_at, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id, cancellationId, group.businessOrderId(), group.businessId(),
                policy.snapshotId(), policy.version(), policy.mode(), group.fulfillmentStatus(),
                group.cancellationStatus(), timestamp(group.cancellationCutoffAt()), Timestamp.from(now));
    }

    public PolicyEvidence policyEvidence(String checkoutId, GroupRow group) {
        return jdbc.query("""
                        SELECT p.id, p.version_code, p.paid_order_cancellation_mode,
                               COUNT(i.id) item_count,
                               SUM(CASE WHEN i.policy_version = p.version_code THEN 0 ELSE 1 END) mismatch_count
                        FROM checkout_policy_snapshots p
                        LEFT JOIN order_items i ON i.business_order_id = ?
                        WHERE p.checkout_id = ? AND p.business_id = ?
                        GROUP BY p.id, p.version_code, p.paid_order_cancellation_mode
                        """, (rs, n) -> new PolicyEvidence(rs.getString("id"), rs.getString("version_code"),
                        rs.getString("paid_order_cancellation_mode"), rs.getInt("item_count"),
                        rs.getInt("mismatch_count")), group.businessOrderId(), checkoutId,
                group.businessId()).stream().findFirst().orElse(null);
    }

    public void transitionGroup(String businessOrderId, Instant now) {
        int changed = jdbc.update("""
                        UPDATE business_orders SET cancellation_status = 'CANCELLATION_PENDING', updated_at = ?
                        WHERE id = ? AND cancellation_status = 'NONE'
                        """, Timestamp.from(now), businessOrderId);
        if (changed != 1) throw new IllegalStateException("Business order cancellation transition was lost.");
    }

    public void insertGroupHistory(String id, String cancellationId, GroupRow group,
            String correlationId, String requestId, Instant now) {
        jdbc.update("""
                        INSERT INTO business_order_cancellation_history (
                            id, business_order_id, business_id, cancellation_request_id, from_status,
                            to_status, reason_code, correlation_id, causation_id, created_at
                        ) VALUES (?, ?, ?, ?, 'NONE', 'CANCELLATION_PENDING',
                            'ADMIN_CANCELLATION_REQUESTED', ?, ?, ?)
                        """, id, group.businessOrderId(), group.businessId(), cancellationId,
                correlationId, requestId, Timestamp.from(now));
    }

    public void insertOrderHistory(String id, String orderId, String actorId,
            String correlationId, String requestId, Instant now) {
        jdbc.update("""
                        INSERT INTO order_status_history (
                            id, order_id, from_status, to_status, reason_code, actor_scope,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, ?, 'CONFIRMED', 'CANCELLATION_REQUESTED',
                            'ADMIN_CANCELLATION_REQUESTED', 'PLATFORM_ADMIN', ?, ?, ?)
                        """, id, orderId, correlationId, requestId, Timestamp.from(now));
    }

    public void insertAdminEvent(String eventId, String orderId, String actorId, String actorDisplay,
            String reasonCode, String reason, String correlationId, String requestId, Instant now) {
        jdbc.update("""
                        INSERT INTO order_admin_events (
                            event_id, order_id, event_type, occurred_at, actor_admin_id,
                            actor_display_name, previous_state, new_state, reason_code, reason,
                            correlation_id, request_id, safe_metadata
                        ) VALUES (?, ?, 'ADMIN_ORDER_CANCELLATION_REQUESTED', ?, ?, ?,
                            'CONFIRMED', 'CANCELLATION_REQUESTED', ?, ?, ?, ?, JSON_OBJECT())
                        """, eventId, orderId, Timestamp.from(now), actorId, actorDisplay,
                reasonCode, reason, correlationId, requestId);
    }

    public void insertOutbox(String id, String orderId, String payload, String correlationId,
            String requestId, Instant now) {
        jdbc.update("""
                        INSERT INTO order_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version, payload_json,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, 'ORDER', ?, 'order.cancellation_requested', 1,
                            CAST(? AS JSON), ?, ?, ?)
                        """, id, orderId, payload, correlationId, requestId, Timestamp.from(now));
    }

    public void completeCommand(String commandId, String cancellationId, String responseJson, Instant now) {
        int changed = jdbc.update("""
                        UPDATE admin_order_cancellation_commands SET state = 'COMPLETED',
                            cancellation_request_id = ?, response_json = CAST(? AS JSON), updated_at = ?
                        WHERE id = ? AND state = 'IN_PROGRESS'
                        """, cancellationId, responseJson, Timestamp.from(now), commandId);
        if (changed != 1) throw new IllegalStateException("Admin cancellation command completion was lost.");
    }

    private void appendFilters(StringBuilder sql, List<Object> args, SearchFilter filter) {
        if (filter.query() != null) {
            sql.append(" AND (o.id LIKE ? OR o.order_number LIKE ?)");
            args.add("%" + filter.query() + "%"); args.add("%" + filter.query() + "%");
        }
        if (filter.buyerId() != null) { sql.append(" AND o.buyer_id = ?"); args.add(filter.buyerId()); }
        if (filter.businessId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM business_orders bo WHERE bo.order_id=o.id AND bo.business_id=?)");
            args.add(filter.businessId());
        }
        if (filter.listingId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id=o.id AND oi.listing_id=?)");
            args.add(filter.listingId());
        }
        if (filter.status() != null) { sql.append(" AND o.status = ?"); args.add(filter.status()); }
        if (filter.paymentStatus() != null) { sql.append(" AND o.payment_status = ?"); args.add(filter.paymentStatus()); }
        if (filter.fulfillmentStatus() != null) {
            sql.append(" AND (").append(FULFILLMENT_STATUS_SQL).append(") = ?");
            args.add(filter.fulfillmentStatus());
        }
        if (filter.createdFrom() != null) { sql.append(" AND o.created_at >= ?"); args.add(Timestamp.from(filter.createdFrom())); }
        if (filter.createdTo() != null) { sql.append(" AND o.created_at < ?"); args.add(Timestamp.from(filter.createdTo())); }
    }

    private String sortExpression(String sort) {
        return switch (sort) {
            case "createdAt,asc" -> "o.created_at ASC, o.id ASC";
            case "updatedAt,desc" -> "o.updated_at DESC, o.id DESC";
            case "updatedAt,asc" -> "o.updated_at ASC, o.id ASC";
            case "total,desc" -> "o.total DESC, o.id DESC";
            case "total,asc" -> "o.total ASC, o.id ASC";
            default -> "o.created_at DESC, o.id DESC";
        };
    }

    private static SearchRow searchRow(ResultSet rs, int n) throws SQLException {
        String ids = rs.getString("business_ids");
        return new SearchRow(rs.getString("id"), rs.getString("buyer_id"), rs.getString("status"),
                rs.getString("payment_status"), rs.getString("fulfillment_status"), rs.getBigDecimal("total"),
                rs.getString("currency"), rs.getInt("item_count"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("version"),
                ids == null || ids.isBlank() ? List.of() : Arrays.asList(ids.split(",")));
    }

    private static TimelineRow timelineRow(ResultSet rs, int n) throws SQLException {
        return new TimelineRow(rs.getString("event_id"), instant(rs, "occurred_at"),
                rs.getString("event_type"), rs.getString("actor_type"), rs.getString("actor_id"),
                rs.getString("actor_display_name"), rs.getString("previous_state"),
                rs.getString("new_state"), rs.getString("reason"), rs.getString("correlation_id"),
                rs.getString("request_id"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        return rs.getTimestamp(name).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    public record SearchFilter(String query, String buyerId, String businessId, String listingId,
            String status, String paymentStatus, String fulfillmentStatus, Instant createdFrom,
            Instant createdTo, int page, int size, String sort) { }
    public record SearchResult(List<SearchRow> rows, long total) { }
    public record SearchRow(String orderId, String buyerId, String status, String paymentStatus,
            String fulfillmentStatus, BigDecimal total, String currency, int itemCount,
            Instant createdAt, Instant updatedAt, long version, List<String> businessIds) { }
    public record DetailRow(String orderId, String orderNumber, String checkoutId, String paymentIntentId,
            String buyerId, String status, String paymentStatus, String currency, BigDecimal subtotal,
            BigDecimal shipping, BigDecimal tax, BigDecimal discount, BigDecimal total, long version,
            Instant createdAt, Instant updatedAt, String checkoutStatus, long checkoutVersion,
            long cartVersion, String cartSnapshotHash, Instant checkoutExpiresAt, String reservationId,
            String reservationStatus, Long reservationVersion, String releaseStatus) { }
    public record GroupRow(String businessOrderId, String businessId, String storeId, String storeName,
            String fulfillmentStatus, String cancellationStatus, long version, Instant cancellationCutoffAt) { }
    public record ItemRow(String itemId, String businessOrderId, int lineNumber, String listingId,
            String businessId, String storeId, long catalogVersion, String title, String sku,
            String condition, String thumbnailUrl, int quantity, BigDecimal unitPrice, String currency,
            BigDecimal lineSubtotal, BigDecimal shipping, BigDecimal tax, BigDecimal discount,
            BigDecimal lineTotal, String policyVersion) { }
    public record AddressRow(String label, String recipientName, String phone, String line1, String line2,
            String city, String region, String postalCode, String countryCode) { }
    public record PolicyRow(String businessId, String version, String mode, String cancellationText,
            String shippingText, String returnText) { }
    public record CancellationRow(String requestId, String status, String actorType, String actorId,
            String reasonCode, String reason, Instant requestedAt, Instant decidedAt, Instant completedAt,
            String inventoryStatus, String refundStatus, String refundId, String refundReference,
            BigDecimal refundAmount, String refundCurrency) { }
    public record RefundRow(String source, String status, String refundId, String providerReference,
            BigDecimal amount, String currency, Instant completedAt) { }
    public record TimelineRow(String eventId, Instant occurredAt, String eventType, String actorType,
            String actorId, String actorDisplayName, String previousState, String newState, String reason,
            String correlationId, String requestId) { }
    public record LockedOrder(String orderId, String checkoutId, String paymentIntentId, String buyerId,
            String status, String paymentStatus, BigDecimal total, String currency, long version) { }
    public record PolicyEvidence(String snapshotId, String version, String mode, int itemCount,
            int mismatchCount) { }
    public record AdminCommand(String id, String orderId, String requestHash, String state,
            String cancellationRequestId, String responseJson) { }
}
