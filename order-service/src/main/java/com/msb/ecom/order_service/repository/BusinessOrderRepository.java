package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.BusinessOrderView;
import com.msb.ecom.order_service.model.BusinessFulfillmentView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class BusinessOrderRepository {

    private static final String HEADER_SELECT = """
            SELECT bo.id, bo.seller_order_number, bo.business_id, bo.store_id,
                   bo.fulfillment_status, bo.cancellation_status, bo.order_id,
                   o.order_number, o.payment_status, counts.item_count,
                   counts.total_quantity, bo.subtotal, bo.total, o.currency,
                   CASE WHEN ? THEN bo.platform_fee_projection ELSE NULL END
                       AS platform_fee_projection,
                   o.confirmed_at, bo.created_at, bo.updated_at, bo.version
            FROM business_orders bo%s
            """;
    private static final String HEADER_JOINS = """
            JOIN orders o ON o.id = bo.order_id
            JOIN (
                SELECT business_order_id, COUNT(*) AS item_count,
                       SUM(quantity) AS total_quantity
                FROM order_items
                GROUP BY business_order_id
            ) counts ON counts.business_order_id = bo.id
            """;

    private final JdbcTemplate jdbc;

    public BusinessOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Reads one deterministic queue page with business isolation in the SQL predicate.
    public List<BusinessOrderView> findPage(
            String businessId,
            String status,
            Instant beforeCreatedAt,
            String beforeBusinessOrderId,
            int limit,
            boolean includeFinance) {
        String indexHint = "CANCELLED".equals(status)
                ? " FORCE INDEX (idx_business_order_cancellation_queue)"
                : status == null
                        ? " FORCE INDEX (idx_business_order_all_queue)"
                        : " FORCE INDEX (idx_business_order_queue)";
        StringBuilder sql = new StringBuilder(HEADER_SELECT.formatted(indexHint))
                .append(HEADER_JOINS)
                .append(" WHERE bo.business_id = ?");
        java.util.ArrayList<Object> arguments = new java.util.ArrayList<>();
        arguments.add(includeFinance);
        arguments.add(businessId);
        if ("CANCELLED".equals(status)) {
            sql.append(" AND bo.cancellation_status = 'CANCELLED'");
        } else if (status != null) {
            sql.append(" AND bo.fulfillment_status = ?");
            arguments.add(status);
            sql.append(" AND bo.cancellation_status <> 'CANCELLED'");
        }
        if (beforeCreatedAt != null) {
            sql.append("""
                     AND (bo.created_at < ?
                          OR (bo.created_at = ? AND bo.id < ?))
                    """);
            arguments.add(Timestamp.from(beforeCreatedAt));
            arguments.add(Timestamp.from(beforeCreatedAt));
            arguments.add(beforeBusinessOrderId);
        }
        sql.append(" ORDER BY bo.created_at DESC, bo.id DESC LIMIT ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), BusinessOrderRepository::mapHeader, arguments.toArray());
    }

    // Reads one group only when both its ID and owning business match.
    public Optional<BusinessOrderView> findOwnedDetail(
            String businessId,
            String businessOrderId,
            boolean includeFinance) {
        Optional<BusinessOrderView> header = jdbc.query(
                        HEADER_SELECT.formatted("") + HEADER_JOINS
                                + " WHERE bo.id = ? AND bo.business_id = ?",
                        BusinessOrderRepository::mapHeader,
                        includeFinance,
                        businessOrderId,
                        businessId)
                .stream()
                .findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        BusinessOrderView current = header.get();
        List<BusinessOrderView.Item> items = jdbc.query("""
                        SELECT listing_id, title, sku, item_condition, thumbnail_url,
                               unit_price, currency, quantity, line_total, policy_version
                        FROM order_items
                        WHERE business_order_id = ? AND business_id = ?
                        ORDER BY line_number, id
                        """,
                (rs, rowNum) -> new BusinessOrderView.Item(
                        rs.getString("listing_id"),
                        rs.getString("title"),
                        rs.getString("sku"),
                        rs.getString("item_condition"),
                        rs.getString("thumbnail_url"),
                        rs.getBigDecimal("unit_price"),
                        rs.getString("currency"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("line_total"),
                        rs.getString("policy_version")),
                businessOrderId,
                businessId);
        BusinessOrderView.Address address = jdbc.query("""
                        SELECT oa.recipient_name, oa.phone, oa.line1, oa.line2, oa.city,
                               oa.region, oa.postal_code, oa.country_code
                        FROM order_addresses oa
                        JOIN business_orders bo ON bo.order_id = oa.order_id
                        WHERE bo.id = ? AND bo.business_id = ?
                          AND oa.address_type = 'SHIPPING'
                        """,
                (rs, rowNum) -> new BusinessOrderView.Address(
                        rs.getString("recipient_name"),
                        rs.getString("phone"),
                        rs.getString("line1"),
                        rs.getString("line2"),
                        rs.getString("city"),
                        rs.getString("region"),
                        rs.getString("postal_code"),
                        rs.getString("country_code")),
                businessOrderId,
                businessId).stream().findFirst().orElse(null);
        List<BusinessFulfillmentView.TimelineEntry> timeline = timeline(businessOrderId);
        BusinessFulfillmentView.Shipment shipment = shipment(businessId, businessOrderId);
        return Optional.of(withDetail(current, items, address, timeline, shipment));
    }

    private static BusinessOrderView mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new BusinessOrderView(
                rs.getString("id"),
                rs.getString("seller_order_number"),
                rs.getString("business_id"),
                rs.getString("store_id"),
                rs.getString("fulfillment_status"),
                rs.getString("cancellation_status"),
                rs.getString("order_id"),
                rs.getString("order_number"),
                rs.getString("payment_status"),
                rs.getInt("item_count"),
                rs.getInt("total_quantity"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("total"),
                rs.getString("currency"),
                rs.getBigDecimal("platform_fee_projection"),
                rs.getTimestamp("confirmed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                List.of(),
                null,
                rs.getLong("version"),
                List.of(),
                null);
    }

    private static BusinessOrderView withDetail(
            BusinessOrderView header,
            List<BusinessOrderView.Item> items,
            BusinessOrderView.Address address,
            List<BusinessFulfillmentView.TimelineEntry> timeline,
            BusinessFulfillmentView.Shipment shipment) {
        return new BusinessOrderView(
                header.businessOrderId(),
                header.sellerOrderNumber(),
                header.businessId(),
                header.storeId(),
                header.status(),
                header.cancellationStatus(),
                header.buyerOrderId(),
                header.buyerOrderNumber(),
                header.paymentStatus(),
                header.itemCount(),
                header.totalQuantity(),
                header.subtotal(),
                header.totalAmount(),
                header.currency(),
                header.platformFeeProjection(),
                header.confirmedAt(),
                header.createdAt(),
                header.updatedAt(),
                List.copyOf(items),
                address,
                header.version(),
                List.copyOf(timeline),
                shipment);
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

    private BusinessFulfillmentView.Shipment shipment(
            String businessId,
            String businessOrderId) {
        return jdbc.query("""
                        SELECT id, source, carrier_display_name, service_display_name,
                               tracking_number, status, version, shipped_at, delivered_at,
                               created_at, updated_at
                        FROM shipments
                        WHERE business_id = ? AND business_order_id = ?
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
                businessId, businessOrderId).stream().findFirst().orElse(null);
    }
}
