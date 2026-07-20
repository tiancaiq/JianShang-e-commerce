package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutReleaseStatus;
import com.msb.ecom.order_service.model.CheckoutStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CheckoutRepository {

    private final JdbcTemplate jdbc;

    public CheckoutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Inserts the durable checkout and every immutable snapshot before inventory is called.
    public void insert(CheckoutAggregate checkout) {
        jdbc.update("""
                        INSERT INTO checkout_sessions (
                            id, buyer_id, status, version, cart_version, cart_snapshot_hash,
                            currency, subtotal, shipping, tax, discount, total, expires_at,
                            release_status, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                checkout.id(),
                checkout.buyerId(),
                checkout.status().name(),
                checkout.version(),
                checkout.cartVersion(),
                checkout.cartSnapshotHash(),
                checkout.currency(),
                checkout.subtotal(),
                checkout.shipping(),
                checkout.tax(),
                checkout.discount(),
                checkout.total(),
                Timestamp.from(checkout.expiresAt()),
                checkout.releaseStatus().name(),
                Timestamp.from(checkout.createdAt()),
                Timestamp.from(checkout.updatedAt()));
        insertAddress(checkout);
        checkout.policies().forEach(policy -> insertPolicy(checkout.id(), policy, checkout.createdAt()));
        checkout.items().forEach(item -> insertItem(checkout.id(), item, checkout.createdAt()));
        checkout.shippingQuotes().forEach(quote -> insertShipping(checkout.id(), quote));
        insertTax(checkout.id(), checkout.taxQuote());
    }

    public Optional<CheckoutAggregate> findOwned(String checkoutId, String buyerId) {
        return header("""
                SELECT * FROM checkout_sessions
                WHERE id = ? AND buyer_id = ?
                """, checkoutId, buyerId).map(this::hydrate);
    }

    public Optional<CheckoutAggregate> find(String checkoutId) {
        return header("SELECT * FROM checkout_sessions WHERE id = ?", checkoutId).map(this::hydrate);
    }

    public Optional<CheckoutAggregate> lockOwned(String checkoutId, String buyerId) {
        return header("""
                SELECT * FROM checkout_sessions
                WHERE id = ? AND buyer_id = ?
                FOR UPDATE
                """, checkoutId, buyerId).map(this::hydrate);
    }

    public Optional<CheckoutAggregate> lock(String checkoutId) {
        return header("SELECT * FROM checkout_sessions WHERE id = ? FOR UPDATE", checkoutId).map(this::hydrate);
    }

    public Optional<CheckoutAggregate> findActiveByBuyer(String buyerId) {
        return header("""
                SELECT * FROM checkout_sessions
                WHERE buyer_id = ? AND status IN (
                    'RESERVING', 'PENDING_PAYMENT', 'PAYMENT_PROCESSING', 'PAYMENT_REVIEW'
                )
                ORDER BY created_at, id LIMIT 1
                """, buyerId).map(this::hydrate);
    }

    public int markPending(
            String checkoutId,
            String reservationId,
            String reservationStatus,
            long reservationVersion,
            Instant now) {
        return jdbc.update("""
                        UPDATE checkout_sessions
                        SET status = 'PENDING_PAYMENT',
                            reservation_id = ?,
                            reservation_status = ?,
                            reservation_version = ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND status = 'RESERVING'
                        """,
                reservationId,
                reservationStatus,
                reservationVersion,
                Timestamp.from(now),
                checkoutId);
    }

    public int markFailed(String checkoutId, String failureCode, Instant now) {
        return jdbc.update("""
                        UPDATE checkout_sessions
                        SET status = 'FAILED',
                            failure_code = ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND status = 'RESERVING'
                        """,
                failureCode,
                Timestamp.from(now),
                checkoutId);
    }

    public int cancel(String checkoutId, Instant now) {
        return jdbc.update("""
                        UPDATE checkout_sessions
                        SET status = 'CANCELLED',
                            release_status = 'PENDING',
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND status = 'PENDING_PAYMENT'
                        """,
                Timestamp.from(now),
                checkoutId);
    }

    public int expire(String checkoutId, Instant now) {
        return jdbc.update("""
                        UPDATE checkout_sessions
                        SET status = 'EXPIRED',
                            release_status = CASE
                                WHEN reservation_id IS NULL THEN 'COMPLETE' ELSE 'PENDING'
                            END,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND status IN ('PENDING_PAYMENT', 'RESERVING')
                        """,
                Timestamp.from(now),
                checkoutId);
    }

    // Claims the only normal paid-event transition before inventory commit.
    public int markPaymentProcessing(String checkoutId, Instant now) {
        return jdbc.update("""
                        UPDATE checkout_sessions
                        SET status = 'PAYMENT_PROCESSING',
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND status = 'PENDING_PAYMENT'
                        """,
                Timestamp.from(now),
                checkoutId);
    }

    // Completes checkout only after the matching reservation is committed.
    public int markCompleted(
            String checkoutId,
            String reservationStatus,
            long reservationVersion,
            Instant now) {
        return jdbc.update("""
                        UPDATE checkout_sessions
                        SET status = 'COMPLETED',
                            reservation_status = ?,
                            reservation_version = ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND status = 'PAYMENT_PROCESSING'
                        """,
                reservationStatus,
                reservationVersion,
                Timestamp.from(now),
                checkoutId);
    }

    // Routes a paid checkout to manual recovery without creating an order.
    public int markPaymentReview(String checkoutId, Instant now) {
        return jdbc.update("""
                        UPDATE checkout_sessions
                        SET status = 'PAYMENT_REVIEW',
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND status = 'PAYMENT_PROCESSING'
                        """,
                Timestamp.from(now),
                checkoutId);
    }

    public void releaseComplete(String checkoutId, String observedStatus, long observedVersion, Instant now) {
        jdbc.update("""
                        UPDATE checkout_sessions
                        SET release_status = 'COMPLETE',
                            reservation_status = ?,
                            reservation_version = ?,
                            last_release_error_code = NULL,
                            release_attempts = release_attempts + 1,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ? AND release_status = 'PENDING'
                        """,
                observedStatus,
                observedVersion,
                Timestamp.from(now),
                checkoutId);
    }

    public void releasePendingFailure(String checkoutId, String errorCode, Instant now) {
        jdbc.update("""
                        UPDATE checkout_sessions
                        SET last_release_error_code = ?,
                            release_attempts = release_attempts + 1,
                            updated_at = ?
                        WHERE id = ? AND release_status = 'PENDING'
                        """,
                errorCode,
                Timestamp.from(now),
                checkoutId);
    }

    public List<String> lockDueExpiryIds(Instant now, int limit) {
        return jdbc.query("""
                        SELECT id FROM checkout_sessions
                        WHERE status IN ('PENDING_PAYMENT', 'RESERVING') AND expires_at <= ?
                        ORDER BY expires_at, id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, rowNum) -> rs.getString(1),
                Timestamp.from(now),
                limit);
    }

    public List<String> reservingIds(int limit) {
        return jdbc.query("""
                        SELECT id FROM checkout_sessions
                        WHERE status = 'RESERVING'
                        ORDER BY updated_at, id
                        LIMIT ?
                        """,
                (rs, rowNum) -> rs.getString(1),
                limit);
    }

    public List<String> releasePendingIds(int limit) {
        return jdbc.query("""
                        SELECT id FROM checkout_sessions
                        WHERE release_status = 'PENDING'
                        ORDER BY updated_at, id
                        LIMIT ?
                        """,
                (rs, rowNum) -> rs.getString(1),
                limit);
    }

    public Optional<CheckoutIdempotencyRecord> idempotency(String callerScope, String key) {
        return jdbc.query("""
                        SELECT caller_scope, idempotency_key, request_hash, operation,
                               state, checkout_id, http_status, response_json
                        FROM order_idempotency_records
                        WHERE caller_scope = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new CheckoutIdempotencyRecord(
                        rs.getString("caller_scope"),
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getString("operation"),
                        rs.getString("state"),
                        rs.getString("checkout_id"),
                        (Integer) rs.getObject("http_status"),
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
            String checkoutId,
            Instant now,
            Instant expiresAt) {
        jdbc.update("""
                        INSERT INTO order_idempotency_records (
                            id, caller_scope, idempotency_key, request_hash, operation,
                            state, checkout_id, created_at, updated_at, expires_at
                        ) VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
                        """,
                id,
                callerScope,
                key,
                requestHash,
                operation,
                checkoutId,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(expiresAt));
    }

    public void completeIdempotency(
            String callerScope,
            String key,
            int httpStatus,
            String responseJson,
            Instant now) {
        jdbc.update("""
                        UPDATE order_idempotency_records
                        SET state = 'COMPLETED', http_status = ?, response_json = ?, updated_at = ?
                        WHERE caller_scope = ? AND idempotency_key = ?
                        """,
                httpStatus,
                responseJson,
                Timestamp.from(now),
                callerScope,
                key);
    }

    public void history(
            String id,
            String checkoutId,
            String fromStatus,
            String toStatus,
            String reason,
            String commandId,
            String correlationId,
            Instant now) {
        history(
                id,
                checkoutId,
                fromStatus,
                toStatus,
                reason,
                commandId,
                correlationId,
                null,
                now);
    }

    public void history(
            String id,
            String checkoutId,
            String fromStatus,
            String toStatus,
            String reason,
            String commandId,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO checkout_status_history (
                            id, checkout_id, from_status, to_status, reason_code,
                            command_id, correlation_id, causation_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                checkoutId,
                fromStatus,
                toStatus,
                reason,
                commandId,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    public void outbox(
            String id,
            String checkoutId,
            String eventType,
            String payloadJson,
            String correlationId,
            String causationId,
            Instant now) {
        jdbc.update("""
                        INSERT INTO order_outbox_events (
                            id, aggregate_type, aggregate_id, event_type, event_version,
                            payload_json, correlation_id, causation_id, created_at
                        ) VALUES (?, 'CHECKOUT', ?, ?, 1, ?, ?, ?, ?)
                        """,
                id,
                checkoutId,
                eventType,
                payloadJson,
                correlationId,
                causationId,
                Timestamp.from(now));
    }

    private void insertAddress(CheckoutAggregate checkout) {
        var address = checkout.address();
        jdbc.update("""
                        INSERT INTO checkout_addresses (
                            checkout_id, source_address_id, source_version, label, recipient_name,
                            phone, line1, line2, city, region, postal_code, country_code, snapshotted_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                checkout.id(),
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
                Timestamp.from(checkout.createdAt()));
    }

    private void insertPolicy(String checkoutId, CheckoutAggregate.Policy policy, Instant now) {
        jdbc.update("""
                        INSERT INTO checkout_policy_snapshots (
                            id, checkout_id, business_id, store_id, source_policy_id, source,
                            version_code, shipping_text, cancellation_text, return_text, snapshotted_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                policy.id(),
                checkoutId,
                policy.businessId(),
                policy.storeId(),
                policy.sourcePolicyId(),
                policy.source(),
                policy.version(),
                policy.shippingText(),
                policy.cancellationText(),
                policy.returnText(),
                Timestamp.from(now));
    }

    private void insertItem(String checkoutId, CheckoutAggregate.Item item, Instant now) {
        jdbc.update("""
                        INSERT INTO checkout_items (
                            id, checkout_id, line_number, listing_id, business_id, store_id,
                            catalog_version, title, sku, item_condition, thumbnail_url, quantity,
                            unit_price, currency, line_subtotal, shipping_allocation, tax_allocation,
                            discount_allocation, line_total, policy_snapshot_id, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                item.id(),
                checkoutId,
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
                item.policySnapshotId(),
                Timestamp.from(now));
    }

    private void insertShipping(String checkoutId, CheckoutAggregate.ShippingQuote quote) {
        jdbc.update("""
                        INSERT INTO checkout_shipping_quotes (
                            id, checkout_id, business_id, store_id, method_code, amount,
                            currency, adapter, quoted_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                quote.id(),
                checkoutId,
                quote.businessId(),
                quote.storeId(),
                quote.methodCode(),
                quote.amount(),
                quote.currency(),
                quote.adapter(),
                Timestamp.from(quote.quotedAt()));
    }

    private void insertTax(String checkoutId, CheckoutAggregate.TaxQuote quote) {
        jdbc.update("""
                        INSERT INTO checkout_tax_quotes (
                            id, checkout_id, amount, currency, adapter, quoted_at
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """,
                quote.id(),
                checkoutId,
                quote.amount(),
                quote.currency(),
                quote.adapter(),
                Timestamp.from(quote.quotedAt()));
    }

    private Optional<Header> header(String sql, Object... args) {
        return jdbc.query(sql, this::headerRow, args).stream().findFirst();
    }

    private Header headerRow(ResultSet rs, int rowNum) throws SQLException {
        return new Header(
                rs.getString("id"),
                rs.getString("buyer_id"),
                CheckoutStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                rs.getLong("cart_version"),
                rs.getString("cart_snapshot_hash"),
                rs.getString("currency"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("shipping"),
                rs.getBigDecimal("tax"),
                rs.getBigDecimal("discount"),
                rs.getBigDecimal("total"),
                instant(rs, "expires_at"),
                rs.getString("reservation_id"),
                rs.getString("reservation_status"),
                (Long) rs.getObject("reservation_version"),
                CheckoutReleaseStatus.valueOf(rs.getString("release_status")),
                rs.getString("failure_code"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private CheckoutAggregate hydrate(Header header) {
        CheckoutAggregate.Address address = jdbc.queryForObject("""
                        SELECT * FROM checkout_addresses WHERE checkout_id = ?
                        """,
                (rs, rowNum) -> new CheckoutAggregate.Address(
                        rs.getString("source_address_id"),
                        rs.getLong("source_version"),
                        rs.getString("label"),
                        rs.getString("recipient_name"),
                        rs.getString("phone"),
                        rs.getString("line1"),
                        rs.getString("line2"),
                        rs.getString("city"),
                        rs.getString("region"),
                        rs.getString("postal_code"),
                        rs.getString("country_code")),
                header.id());
        List<CheckoutAggregate.Item> items = jdbc.query("""
                        SELECT item.*, policy.version_code
                        FROM checkout_items item
                        JOIN checkout_policy_snapshots policy ON policy.id = item.policy_snapshot_id
                        WHERE item.checkout_id = ?
                        ORDER BY item.line_number
                        """,
                (rs, rowNum) -> new CheckoutAggregate.Item(
                        rs.getString("id"),
                        rs.getInt("line_number"),
                        rs.getString("listing_id"),
                        rs.getString("business_id"),
                        rs.getString("store_id"),
                        rs.getLong("catalog_version"),
                        rs.getString("title"),
                        rs.getString("sku"),
                        rs.getString("item_condition"),
                        rs.getString("thumbnail_url"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getString("currency"),
                        rs.getBigDecimal("line_subtotal"),
                        rs.getBigDecimal("shipping_allocation"),
                        rs.getBigDecimal("tax_allocation"),
                        rs.getBigDecimal("discount_allocation"),
                        rs.getBigDecimal("line_total"),
                        rs.getString("policy_snapshot_id"),
                        rs.getString("version_code")),
                header.id());
        List<CheckoutAggregate.Policy> policies = jdbc.query("""
                        SELECT * FROM checkout_policy_snapshots
                        WHERE checkout_id = ? ORDER BY business_id
                        """,
                (rs, rowNum) -> new CheckoutAggregate.Policy(
                        rs.getString("id"),
                        rs.getString("business_id"),
                        rs.getString("store_id"),
                        rs.getString("source_policy_id"),
                        rs.getString("source"),
                        rs.getString("version_code"),
                        rs.getString("shipping_text"),
                        rs.getString("cancellation_text"),
                        rs.getString("return_text")),
                header.id());
        List<CheckoutAggregate.ShippingQuote> shippingQuotes = jdbc.query("""
                        SELECT * FROM checkout_shipping_quotes
                        WHERE checkout_id = ? ORDER BY business_id
                        """,
                (rs, rowNum) -> new CheckoutAggregate.ShippingQuote(
                        rs.getString("id"),
                        rs.getString("business_id"),
                        rs.getString("store_id"),
                        rs.getString("method_code"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("adapter"),
                        instant(rs, "quoted_at")),
                header.id());
        CheckoutAggregate.TaxQuote taxQuote = jdbc.queryForObject("""
                        SELECT * FROM checkout_tax_quotes WHERE checkout_id = ?
                        """,
                (rs, rowNum) -> new CheckoutAggregate.TaxQuote(
                        rs.getString("id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("adapter"),
                        instant(rs, "quoted_at")),
                header.id());
        return new CheckoutAggregate(
                header.id(),
                header.buyerId(),
                header.status(),
                header.version(),
                header.cartVersion(),
                header.cartSnapshotHash(),
                header.currency(),
                header.subtotal(),
                header.shipping(),
                header.tax(),
                header.discount(),
                header.total(),
                header.expiresAt(),
                header.reservationId(),
                header.reservationStatus(),
                header.reservationVersion(),
                header.releaseStatus(),
                header.failureCode(),
                header.createdAt(),
                header.updatedAt(),
                address,
                items,
                shippingQuotes,
                taxQuote,
                policies);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Header(
            String id,
            String buyerId,
            CheckoutStatus status,
            long version,
            long cartVersion,
            String cartSnapshotHash,
            String currency,
            java.math.BigDecimal subtotal,
            java.math.BigDecimal shipping,
            java.math.BigDecimal tax,
            java.math.BigDecimal discount,
            java.math.BigDecimal total,
            Instant expiresAt,
            String reservationId,
            String reservationStatus,
            Long reservationVersion,
            CheckoutReleaseStatus releaseStatus,
            String failureCode,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
