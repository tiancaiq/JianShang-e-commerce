package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.model.CheckoutPaymentBinding;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class CheckoutPaymentBindingRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CheckoutPaymentBindingRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // Persists the validated payment scope once and accepts only an exact idempotent replay.
    public void insertOrVerify(CheckoutPaymentBinding binding) {
        try {
            jdbc.update("""
                            INSERT INTO checkout_payment_intents (
                                payment_intent_id, checkout_id, checkout_version,
                                checkout_snapshot_hash, buyer_id, business_ids_json,
                                amount, currency, expires_at, created_at
                            ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?)
                            """,
                    binding.paymentIntentId(),
                    binding.checkoutId(),
                    binding.checkoutVersion(),
                    binding.checkoutSnapshotHash(),
                    binding.buyerId(),
                    json(binding.businessIds()),
                    binding.amount(),
                    binding.currency(),
                    Timestamp.from(binding.expiresAt()),
                    Timestamp.from(binding.createdAt()));
        } catch (DuplicateKeyException exception) {
            CheckoutPaymentBinding existing = find(binding.paymentIntentId())
                    .or(() -> findByCheckout(binding.checkoutId()))
                    .orElseThrow(() -> conflict());
            if (!same(existing, binding)) {
                throw conflict();
            }
        }
    }

    public Optional<CheckoutPaymentBinding> find(String paymentIntentId) {
        return query("""
                SELECT * FROM checkout_payment_intents
                WHERE payment_intent_id = ?
                """, paymentIntentId);
    }

    public Optional<CheckoutPaymentBinding> lock(String paymentIntentId) {
        return query("""
                SELECT * FROM checkout_payment_intents
                WHERE payment_intent_id = ?
                FOR UPDATE
                """, paymentIntentId);
    }

    public Optional<CheckoutPaymentBinding> findByCheckout(String checkoutId) {
        return query("""
                SELECT * FROM checkout_payment_intents
                WHERE checkout_id = ?
                """, checkoutId);
    }

    private Optional<CheckoutPaymentBinding> query(String sql, Object... args) {
        return jdbc.query(sql, this::map, args).stream().findFirst();
    }

    private CheckoutPaymentBinding map(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new CheckoutPaymentBinding(
                    rs.getString("payment_intent_id"),
                    rs.getString("checkout_id"),
                    rs.getLong("checkout_version"),
                    rs.getString("checkout_snapshot_hash"),
                    rs.getString("buyer_id"),
                    objectMapper.readValue(rs.getString("business_ids_json"), STRING_LIST),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());
        } catch (Exception exception) {
            throw new SQLException("Stored checkout payment binding is invalid.", exception);
        }
    }

    private boolean same(CheckoutPaymentBinding left, CheckoutPaymentBinding right) {
        return left.paymentIntentId().equals(right.paymentIntentId())
                && left.checkoutId().equals(right.checkoutId())
                && left.checkoutVersion() == right.checkoutVersion()
                && left.checkoutSnapshotHash().equals(right.checkoutSnapshotHash())
                && left.buyerId().equals(right.buyerId())
                && left.businessIds().equals(right.businessIds())
                && left.amount().compareTo(right.amount()) == 0
                && left.currency().equals(right.currency())
                && left.expiresAt().equals(right.expiresAt());
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Payment business scope cannot be serialized.", exception);
        }
    }

    private OrderConfirmationException conflict() {
        return new OrderConfirmationException(
                "PAYMENT_BINDING_CONFLICT",
                "The payment intent does not match its checkout binding.");
    }
}
