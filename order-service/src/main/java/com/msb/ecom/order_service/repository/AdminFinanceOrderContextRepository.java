package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.dto.AdminFinanceOrderContext;
import com.msb.ecom.order_service.dto.AdminFinanceOrderContext.Business;
import com.msb.ecom.order_service.dto.AdminFinanceOrderContext.DisputeRecommendation;
import com.msb.ecom.order_service.dto.AdminFinanceOrderContext.Item;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class AdminFinanceOrderContextRepository {
    private final JdbcTemplate jdbc;

    public AdminFinanceOrderContextRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Resolves a page of Payment-owned IDs with three bounded Order queries, avoiding per-row service calls.
    public List<AdminFinanceOrderContext> byPaymentIds(Set<String> paymentIntentIds) {
        if (paymentIntentIds.isEmpty()) return List.of();
        String in = placeholders(paymentIntentIds.size());
        Object[] args = paymentIntentIds.toArray();
        List<OrderRow> orders = jdbc.query("""
                SELECT id,payment_intent_id,order_number,buyer_id,total,currency,created_at
                FROM orders WHERE payment_intent_id IN (%s) ORDER BY created_at,id
                """.formatted(in), (r,n) -> new OrderRow(r.getString("id"),r.getString("payment_intent_id"),
                r.getString("order_number"),r.getString("buyer_id"),r.getBigDecimal("total"),
                r.getString("currency"),r.getTimestamp("created_at")), args);
        if (orders.isEmpty()) return List.of();
        List<String> orderIds = orders.stream().map(OrderRow::orderId).toList();
        String orderIn = placeholders(orderIds.size()); Object[] orderArgs = orderIds.toArray();
        Map<String,List<Business>> businesses = grouped(orderIds);
        jdbc.query("SELECT order_id,business_id,store_name,total FROM business_orders WHERE order_id IN ("+orderIn+") ORDER BY created_at,id",
                (RowCallbackHandler) r -> businesses.get(r.getString("order_id")).add(new Business(r.getString("business_id"),r.getString("store_name"),r.getBigDecimal("total"))), orderArgs);
        Map<String,List<Item>> items = grouped(orderIds);
        jdbc.query("SELECT order_id,listing_id,title,quantity,line_total FROM order_items WHERE order_id IN ("+orderIn+") ORDER BY line_number,id",
                (RowCallbackHandler) r -> items.get(r.getString("order_id")).add(new Item(r.getString("listing_id"),r.getString("title"),r.getInt("quantity"),r.getBigDecimal("line_total"))), orderArgs);
        Map<String,List<DisputeRecommendation>> disputes = grouped(orderIds);
        jdbc.query("""
                SELECT order_id,id,business_order_id,business_id,resolution_type,recommended_refund_amount,
                       currency,resolution_reason,resolved_at
                FROM order_disputes WHERE order_id IN (%s) AND resolution_type IN
                  ('REFUND_RECOMMENDED','PARTIAL_REFUND_RECOMMENDED')
                ORDER BY resolved_at,id
                """.formatted(orderIn), (RowCallbackHandler) r -> disputes.get(r.getString("order_id")).add(new DisputeRecommendation(
                r.getString("id"),r.getString("business_order_id"),r.getString("business_id"),
                r.getString("resolution_type"),r.getBigDecimal("recommended_refund_amount"),
                r.getString("currency"),r.getString("resolution_reason"),instant(r.getTimestamp("resolved_at")))), orderArgs);
        return orders.stream().map(o -> new AdminFinanceOrderContext(o.paymentIntentId(),o.orderId(),o.orderNumber(),
                o.buyerId(),o.total(),o.currency(),instant(o.createdAt()),businesses.get(o.orderId()),
                items.get(o.orderId()),disputes.get(o.orderId()))).toList();
    }

    public Optional<AdminFinanceOrderContext> byOrderId(String orderId) {
        String payment = jdbc.query("SELECT payment_intent_id FROM orders WHERE id=?",
                (r,n) -> r.getString(1),orderId).stream().findFirst().orElse(null);
        return payment == null ? Optional.empty() : byPaymentIds(Set.of(payment)).stream().findFirst();
    }

    private static <T> Map<String,List<T>> grouped(List<String> ids) {
        Map<String,List<T>> result = new LinkedHashMap<>(); ids.forEach(id -> result.put(id,new ArrayList<>())); return result;
    }
    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count,"?")); }
    private static java.time.Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record OrderRow(String orderId,String paymentIntentId,String orderNumber,String buyerId,
            java.math.BigDecimal total,String currency,Timestamp createdAt) { }
}
