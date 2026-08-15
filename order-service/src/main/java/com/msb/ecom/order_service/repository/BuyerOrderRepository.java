package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.BuyerOrderView;
import com.msb.ecom.order_service.model.BusinessFulfillmentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BuyerOrderRepository {

    private final JdbcTemplate jdbc;

    public BuyerOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Reads a deterministic buyer-owned page without loading payment or processing internals.
    public List<BuyerOrderView> findPage(
            String buyerId,
            Instant beforeCreatedAt,
            String beforeOrderId,
            int limit) {
        List<OrderHeader> headers;
        if (beforeCreatedAt == null) {
            headers = jdbc.query("""
                            SELECT id, status, payment_status, total, currency, version,
                                   created_at, updated_at
                            FROM orders
                            WHERE buyer_id = ?
                            ORDER BY created_at DESC, id DESC
                            LIMIT ?
                            """,
                    BuyerOrderRepository::mapHeader,
                    buyerId,
                    limit);
        } else {
            headers = jdbc.query("""
                            SELECT id, status, payment_status, total, currency, version,
                                   created_at, updated_at
                            FROM orders
                            WHERE buyer_id = ?
                              AND (created_at < ? OR (created_at = ? AND id < ?))
                            ORDER BY created_at DESC, id DESC
                            LIMIT ?
                            """,
                    BuyerOrderRepository::mapHeader,
                    buyerId,
                    Timestamp.from(beforeCreatedAt),
                    Timestamp.from(beforeCreatedAt),
                    beforeOrderId,
                    limit);
        }
        return attachGroups(headers, false);
    }

    // Applies buyer ownership in the header query so missing and cross-buyer reads are identical.
    public Optional<BuyerOrderView> findOwnedDetail(String buyerId, String orderId) {
        Optional<OrderHeader> header = jdbc.query("""
                        SELECT id, status, payment_status, total, currency, version,
                               created_at, updated_at
                        FROM orders
                        WHERE id = ? AND buyer_id = ?
                        """,
                BuyerOrderRepository::mapHeader,
                orderId,
                buyerId).stream().findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<BuyerOrderView.Group> groups = groups(List.of(orderId), true)
                .getOrDefault(orderId, List.of());
        BuyerOrderView.Address address = jdbc.query("""
                        SELECT label, recipient_name, phone, line1, line2, city, region,
                               postal_code, country_code
                        FROM order_addresses
                        WHERE order_id = ? AND address_type = 'SHIPPING'
                        """,
                (rs, rowNum) -> new BuyerOrderView.Address(
                        rs.getString("label"),
                        rs.getString("recipient_name"),
                        rs.getString("phone"),
                        rs.getString("line1"),
                        rs.getString("line2"),
                        rs.getString("city"),
                        rs.getString("region"),
                        rs.getString("postal_code"),
                        rs.getString("country_code")),
                orderId).stream().findFirst().orElse(null);
        return Optional.of(header.get().toView(groups, address));
    }

    // Projects only buyer-safe cancellation and fake-refund evidence.
    public CancellationView cancellation(String orderId) {
        CancellationView existing = jdbc.query("""
                        SELECT r.id, r.status, r.requested_at, r.decided_at, r.completed_at,
                               c.inventory_status, c.refund_status, c.refund_id, c.refund_reference,
                               c.amount, c.currency
                        FROM order_cancellation_requests r
                        LEFT JOIN order_cancellation_compensations c
                          ON c.cancellation_request_id = r.id
                        WHERE r.order_id = ?
                        """, (rs, rowNum) -> new CancellationView(
                        false, null, rs.getString("id"), rs.getString("status"),
                        rs.getTimestamp("requested_at").toInstant(),
                        timestamp(rs, "decided_at"), timestamp(rs, "completed_at"),
                        rs.getString("inventory_status"), rs.getString("refund_status"), rs.getString("refund_id"),
                        rs.getString("refund_reference"), rs.getBigDecimal("amount"),
                        rs.getString("currency")), orderId).stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        Eligibility eligibility = jdbc.query("""
                        SELECT COUNT(*) AS group_count,
                               SUM(CASE WHEN bo.fulfillment_status = 'PENDING_ACCEPTANCE'
                                         AND bo.cancellation_status = 'NONE' THEN 0 ELSE 1 END)
                                   AS fulfillment_conflicts,
                               SUM(CASE WHEN policy.paid_order_cancellation_mode = 'BEFORE_FULFILLMENT'
                                         THEN 0 ELSE 1 END) AS policy_conflicts,
                               SUM(CASE WHEN bo.cancellation_cutoff_at > CURRENT_TIMESTAMP(6)
                                         THEN 0 ELSE 1 END) AS cutoff_conflicts
                        FROM business_orders bo
                        JOIN orders o ON o.id = bo.order_id
                        JOIN checkout_policy_snapshots policy
                          ON policy.checkout_id = o.checkout_id
                         AND policy.business_id = bo.business_id
                        WHERE bo.order_id = ?
                        """, (rs, rowNum) -> new Eligibility(
                        rs.getInt("group_count"), rs.getInt("fulfillment_conflicts"),
                        rs.getInt("policy_conflicts"), rs.getInt("cutoff_conflicts")),
                orderId).stream().findFirst().orElse(new Eligibility(0, 1, 1, 1));
        boolean eligible = eligibility.groupCount() > 0
                && eligibility.fulfillmentConflicts() == 0
                && eligibility.policyConflicts() == 0
                && eligibility.cutoffConflicts() == 0;
        String reason = eligible ? null
                : eligibility.fulfillmentConflicts() > 0 ? "FULFILLMENT_STARTED"
                : eligibility.policyConflicts() > 0 ? "POLICY_NOT_ALLOWED"
                : "WINDOW_CLOSED";
        return new CancellationView(eligible, reason, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private List<BuyerOrderView> attachGroups(List<OrderHeader> headers, boolean includeItems) {
        if (headers.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = headers.stream().map(OrderHeader::orderId).toList();
        Map<String, List<BuyerOrderView.Group>> groupsByOrder = groups(orderIds, includeItems);
        return headers.stream()
                .map(header -> header.toView(
                        groupsByOrder.getOrDefault(header.orderId(), List.of()),
                        null))
                .toList();
    }

    private Map<String, List<BuyerOrderView.Group>> groups(
            List<String> orderIds,
            boolean includeItems) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        List<GroupRow> rows = jdbc.query("""
                        SELECT order_id, id, business_id, store_id, store_name,
                               fulfillment_status, total, version
                        FROM business_orders
                        WHERE order_id IN (%s)
                        ORDER BY order_id, created_at, id
                        """.formatted(placeholders),
                BuyerOrderRepository::mapGroup,
                orderIds.toArray());
        Map<String, List<BuyerOrderView.Item>> itemsByGroup =
                includeItems ? items(orderIds, placeholders) : Map.of();
        Map<String, List<BuyerOrderView.Group>> result = new LinkedHashMap<>();
        for (GroupRow row : rows) {
            result.computeIfAbsent(row.orderId(), ignored -> new ArrayList<>())
                    .add(new BuyerOrderView.Group(
                            row.businessOrderId(),
                            row.businessId(),
                            row.storeId(),
                            row.storeName(),
                            row.status(),
                            row.totalAmount(),
                            itemsByGroup.getOrDefault(row.businessOrderId(), List.of()),
                            row.version(),
                            includeItems ? timeline(row.businessOrderId()) : List.of(),
                            includeItems ? shipment(row.businessOrderId()) : null));
        }
        return result;
    }

    private Map<String, List<BuyerOrderView.Item>> items(
            List<String> orderIds,
            String placeholders) {
        List<ItemRow> rows = jdbc.query("""
                        SELECT business_order_id, listing_id, title, business_id, store_id,
                               unit_price, currency, quantity, line_total, policy_version
                        FROM order_items
                        WHERE order_id IN (%s)
                        ORDER BY business_order_id, line_number, id
                        """.formatted(placeholders),
                (rs, rowNum) -> new ItemRow(
                        rs.getString("business_order_id"),
                        new BuyerOrderView.Item(
                                rs.getString("listing_id"),
                                rs.getString("title"),
                                rs.getString("business_id"),
                                rs.getString("store_id"),
                                rs.getBigDecimal("unit_price"),
                                rs.getString("currency"),
                                rs.getInt("quantity"),
                                rs.getBigDecimal("line_total"),
                                rs.getString("policy_version"))),
                orderIds.toArray());
        Map<String, List<BuyerOrderView.Item>> result = new LinkedHashMap<>();
        for (ItemRow row : rows) {
            result.computeIfAbsent(row.businessOrderId(), ignored -> new ArrayList<>())
                    .add(row.item());
        }
        return result;
    }

    private static OrderHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new OrderHeader(
                rs.getString("id"),
                rs.getString("status"),
                rs.getString("payment_status"),
                rs.getBigDecimal("total"),
                rs.getString("currency"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static GroupRow mapGroup(ResultSet rs, int rowNum) throws SQLException {
        return new GroupRow(
                rs.getString("order_id"),
                rs.getString("id"),
                rs.getString("business_id"),
                rs.getString("store_id"),
                rs.getString("store_name"),
                rs.getString("fulfillment_status"),
                rs.getBigDecimal("total"),
                rs.getLong("version"));
    }

    private record OrderHeader(
            String orderId,
            String status,
            String paymentStatus,
            java.math.BigDecimal totalAmount,
            String currency,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        private BuyerOrderView toView(
                List<BuyerOrderView.Group> groups,
                BuyerOrderView.Address address) {
            return new BuyerOrderView(
                    orderId,
                    status,
                    paymentStatus,
                    totalAmount,
                    currency,
                    version,
                    createdAt,
                    updatedAt,
                    List.copyOf(groups),
                    address);
        }
    }

    private record GroupRow(
            String orderId,
            String businessOrderId,
            String businessId,
            String storeId,
            String storeName,
            String status,
            java.math.BigDecimal totalAmount,
            long version
    ) {
    }

    private record ItemRow(
            String businessOrderId,
            BuyerOrderView.Item item
    ) {
    }

    private record Eligibility(
            int groupCount, int fulfillmentConflicts, int policyConflicts, int cutoffConflicts) {
    }

    public record CancellationView(
            boolean eligible,
            String ineligibilityCode,
            String requestId,
            String requestStatus,
            Instant requestedAt,
            Instant decidedAt,
            Instant completedAt,
            String inventoryStatus,
            String refundStatus,
            String refundId,
            String refundReference,
            java.math.BigDecimal refundAmount,
            String refundCurrency
    ) {
    }

    private static Instant timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private List<BusinessFulfillmentView.TimelineEntry> timeline(String businessOrderId) {
        return jdbc.query("""
                        SELECT to_status, created_at
                        FROM business_order_status_history
                        WHERE business_order_id = ?
                        ORDER BY business_order_version
                        """,
                (rs, rowNum) -> new BusinessFulfillmentView.TimelineEntry(
                        rs.getString("to_status"), rs.getTimestamp("created_at").toInstant()),
                businessOrderId);
    }

    private BusinessFulfillmentView.Shipment shipment(String businessOrderId) {
        return jdbc.query("""
                        SELECT id, source, carrier_display_name, service_display_name,
                               tracking_number, status, version, shipped_at, delivered_at,
                               created_at, updated_at
                        FROM shipments
                        WHERE business_order_id = ?
                        """,
                (rs, rowNum) -> new BusinessFulfillmentView.Shipment(
                        rs.getString("id"), rs.getString("source"),
                        rs.getString("carrier_display_name"),
                        rs.getString("service_display_name"),
                        rs.getString("tracking_number"), rs.getString("status"),
                        rs.getLong("version"), rs.getTimestamp("shipped_at").toInstant(),
                        rs.getTimestamp("delivered_at") == null ? null
                                : rs.getTimestamp("delivered_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                businessOrderId).stream().findFirst().orElse(null);
    }
}
