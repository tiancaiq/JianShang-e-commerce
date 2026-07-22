package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.BuyerOrderView;
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
                        SELECT order_id, id, business_id, store_id, fulfillment_status, total
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
                            row.status(),
                            row.totalAmount(),
                            itemsByGroup.getOrDefault(row.businessOrderId(), List.of())));
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
                rs.getString("fulfillment_status"),
                rs.getBigDecimal("total"));
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
            String status,
            java.math.BigDecimal totalAmount
    ) {
    }

    private record ItemRow(
            String businessOrderId,
            BuyerOrderView.Item item
    ) {
    }
}
