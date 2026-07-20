package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutPaymentBinding;
import com.msb.ecom.order_service.model.PaymentEventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class OrderConfirmationRepository {

    private final JdbcTemplate jdbc;

    public OrderConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProcessedPaymentEvent> lockProcessed(String consumerName, String eventId) {
        return jdbc.query("""
                        SELECT consumer_name, event_id, payload_hash, payment_intent_id,
                               checkout_id, state, outcome, safe_error_code, order_id,
                               claim_token, claim_expires_at
                        FROM processed_payment_events
                        WHERE consumer_name = ? AND event_id = ?
                        FOR UPDATE
                        """,
                (rs, rowNum) -> new ProcessedPaymentEvent(
                        rs.getString("consumer_name"),
                        rs.getString("event_id"),
                        rs.getString("payload_hash"),
                        rs.getString("payment_intent_id"),
                        rs.getString("checkout_id"),
                        rs.getString("state"),
                        rs.getString("outcome"),
                        rs.getString("safe_error_code"),
                        rs.getString("order_id"),
                        rs.getString("claim_token"),
                        rs.getTimestamp("claim_expires_at") == null
                                ? null
                                : rs.getTimestamp("claim_expires_at").toInstant()),
                consumerName,
                eventId).stream().findFirst();
    }

    // Starts durable event processing before the inventory side effect.
    public void insertProcessing(
            String consumerName,
            PaymentEventEnvelope event,
            String payloadHash,
            String claimToken,
            Instant claimExpiresAt,
            Instant now) {
        jdbc.update("""
                        INSERT INTO processed_payment_events (
                            consumer_name, event_id, event_type, schema_version, payload_hash,
                            payment_intent_id, checkout_id, state, claim_token, claim_expires_at,
                            correlation_id, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?, ?)
                        """,
                consumerName,
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                payloadHash,
                event.paymentIntentId(),
                event.checkoutId(),
                claimToken,
                Timestamp.from(claimExpiresAt),
                event.correlationId(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    // Reclaims only retryable work or an abandoned expired processing lease.
    public int reclaim(
            String consumerName,
            String eventId,
            String claimToken,
            Instant claimExpiresAt,
            Instant now) {
        return jdbc.update("""
                        UPDATE processed_payment_events
                        SET state = 'PROCESSING',
                            claim_token = ?,
                            claim_expires_at = ?,
                            safe_error_code = NULL,
                            updated_at = ?
                        WHERE consumer_name = ?
                          AND event_id = ?
                          AND (
                              state = 'RETRYABLE'
                              OR (state = 'PROCESSING' AND claim_expires_at <= ?)
                          )
                        """,
                claimToken,
                Timestamp.from(claimExpiresAt),
                Timestamp.from(now),
                consumerName,
                eventId,
                Timestamp.from(now));
    }

    public void markRetryable(
            String consumerName,
            String eventId,
            String claimToken,
            String safeCode,
            Instant now) {
        jdbc.update("""
                        UPDATE processed_payment_events
                        SET state = 'RETRYABLE',
                            safe_error_code = ?,
                            claim_token = NULL,
                            claim_expires_at = NULL,
                            updated_at = ?
                        WHERE consumer_name = ? AND event_id = ?
                          AND state = 'PROCESSING' AND claim_token = ?
                        """,
                safeCode,
                Timestamp.from(now),
                consumerName,
                eventId,
                claimToken);
    }

    public void markRejected(
            String consumerName,
            String eventId,
            String claimToken,
            String safeCode,
            Instant now) {
        jdbc.update("""
                        UPDATE processed_payment_events
                        SET state = 'REJECTED',
                            outcome = 'REJECTED',
                            safe_error_code = ?,
                            claim_token = NULL,
                            claim_expires_at = NULL,
                            processed_at = ?,
                            updated_at = ?
                        WHERE consumer_name = ? AND event_id = ?
                          AND state = 'PROCESSING' AND claim_token = ?
                        """,
                safeCode,
                Timestamp.from(now),
                Timestamp.from(now),
                consumerName,
                eventId,
                claimToken);
    }

    public void markCompleted(
            String consumerName,
            String eventId,
            String claimToken,
            String orderId,
            Instant now) {
        jdbc.update("""
                        UPDATE processed_payment_events
                        SET state = 'COMPLETED',
                            outcome = 'CONFIRMED',
                            order_id = ?,
                            safe_error_code = NULL,
                            claim_token = NULL,
                            claim_expires_at = NULL,
                            processed_at = ?,
                            updated_at = ?
                        WHERE consumer_name = ? AND event_id = ?
                          AND state = 'PROCESSING' AND claim_token = ?
                        """,
                orderId,
                Timestamp.from(now),
                Timestamp.from(now),
                consumerName,
                eventId,
                claimToken);
    }

    public Optional<String> orderIdByCheckout(String checkoutId) {
        return jdbc.query(
                "SELECT id FROM orders WHERE checkout_id = ?",
                (rs, rowNum) -> rs.getString(1),
                checkoutId).stream().findFirst();
    }

    // Inserts the immutable buyer order header from checkout-owned totals.
    public void insertOrder(
            String orderId,
            CheckoutAggregate checkout,
            CheckoutPaymentBinding payment,
            Instant now) {
        jdbc.update("""
                        INSERT INTO orders (
                            id, checkout_id, payment_intent_id, buyer_id, order_number,
                            currency, subtotal, shipping, tax, discount, total,
                            payment_status, status, confirmed_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                  'SUCCEEDED', 'CONFIRMED', ?, ?, ?)
                        """,
                orderId,
                checkout.id(),
                payment.paymentIntentId(),
                checkout.buyerId(),
                orderId,
                checkout.currency(),
                checkout.subtotal(),
                checkout.shipping(),
                checkout.tax(),
                checkout.discount(),
                checkout.total(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public void insertBusinessOrder(
            String id,
            String orderId,
            String businessId,
            String storeId,
            Totals totals,
            Instant now) {
        jdbc.update("""
                        INSERT INTO business_orders (
                            id, order_id, business_id, store_id, seller_order_number,
                            fulfillment_status, cancellation_status, subtotal, shipping,
                            tax, discount, total, platform_fee_projection, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'PENDING_ACCEPTANCE', 'NONE',
                                  ?, ?, ?, ?, ?, NULL, ?, ?)
                        """,
                id,
                orderId,
                businessId,
                storeId,
                id,
                totals.subtotal(),
                totals.shipping(),
                totals.tax(),
                totals.discount(),
                totals.total(),
                Timestamp.from(now),
                Timestamp.from(now));
    }

    public void insertOrderItem(
            String id,
            String orderId,
            String businessOrderId,
            CheckoutAggregate.Item item,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_items (
                            id, order_id, business_order_id, line_number, listing_id,
                            business_id, store_id, catalog_version, title, sku,
                            item_condition, thumbnail_url, quantity, unit_price, currency,
                            line_subtotal, shipping_allocation, tax_allocation,
                            discount_allocation, line_total, policy_version, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                orderId,
                businessOrderId,
                item.lineNumber(),
                item.listingId(),
                item.businessId(),
                item.storeId(),
                item.catalogVersion(),
                item.title(),
                item.sku(),
                item.condition(),
                item.thumbnailUrl(),
                item.quantity(),
                item.unitPrice(),
                item.currency(),
                item.lineSubtotal(),
                item.shippingAllocation(),
                item.taxAllocation(),
                item.discountAllocation(),
                item.lineTotal(),
                item.policyVersion(),
                Timestamp.from(now));
    }

    public void insertAddress(String orderId, CheckoutAggregate.Address address, Instant now) {
        jdbc.update("""
                        INSERT INTO order_addresses (
                            order_id, address_type, source_address_id, source_version, label,
                            recipient_name, phone, line1, line2, city, region, postal_code,
                            country_code, snapshotted_at
                        ) VALUES (?, 'SHIPPING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                orderId,
                address.sourceAddressId(),
                address.sourceVersion(),
                address.label(),
                address.recipientName(),
                address.phone(),
                address.line1(),
                address.line2(),
                address.city(),
                address.region(),
                address.postalCode(),
                address.countryCode(),
                Timestamp.from(now));
    }

    public void insertHistory(
            String id,
            String orderId,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_status_history (
                            id, order_id, from_status, to_status, reason_code, actor_scope,
                            correlation_id, causation_id, created_at
                        ) VALUES (?, ?, NULL, 'CONFIRMED', 'PAYMENT_SUCCEEDED',
                                  'PAYMENT_SERVICE', ?, ?, ?)
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
                        ) VALUES (?, 'ORDER', ?, 'order.confirmed', 1,
                                  CAST(? AS JSON), ?, ?, ?)
                        """,
                id,
                orderId,
                payloadJson,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    public record Totals(
            BigDecimal subtotal,
            BigDecimal shipping,
            BigDecimal tax,
            BigDecimal discount,
            BigDecimal total
    ) {
    }
}
