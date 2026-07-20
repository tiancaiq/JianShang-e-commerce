package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.BuyerOrderView;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuyerOrderRepositoryMySqlIntegrationTests {

    private static final String BUYER_ONE = id(900);
    private static final String BUYER_TWO = id(901);
    private static final Instant BASE = Instant.parse("2026-07-20T01:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    private static JdbcTemplate jdbc;
    private static BuyerOrderRepository repository;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        String jdbcUrl = MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new BuyerOrderRepository(jdbc);
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM order_addresses");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM business_orders");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM checkout_sessions");
    }

    @Test
    void cursorRemainsStableWhenANewerOrderIsInsertedBetweenPages() {
        seedOrder(1, BUYER_ONE, BASE.plusSeconds(30), "25.0000", 1);
        seedOrder(2, BUYER_ONE, BASE.plusSeconds(20), "20.0000", 1);
        seedOrder(3, BUYER_ONE, BASE.plusSeconds(10), "15.0000", 1);

        List<BuyerOrderView> first = repository.findPage(BUYER_ONE, null, null, 2);
        seedOrder(4, BUYER_ONE, BASE.plusSeconds(40), "30.0000", 1);
        BuyerOrderView cursor = first.get(1);
        List<BuyerOrderView> second = repository.findPage(
                BUYER_ONE,
                cursor.createdAt(),
                cursor.orderId(),
                2);

        assertThat(first).extracting(BuyerOrderView::orderId)
                .containsExactly(id(1), id(2));
        assertThat(second).extracting(BuyerOrderView::orderId)
                .containsExactly(id(3));
    }

    @Test
    void sameTimestampUsesDescendingIdAsStableTieBreaker() {
        seedOrder(10, BUYER_ONE, BASE, "10.0000", 1);
        seedOrder(11, BUYER_ONE, BASE, "11.0000", 1);
        seedOrder(12, BUYER_ONE, BASE, "12.0000", 1);

        List<BuyerOrderView> first = repository.findPage(BUYER_ONE, null, null, 2);
        BuyerOrderView cursor = first.get(1);
        List<BuyerOrderView> second = repository.findPage(
                BUYER_ONE,
                cursor.createdAt(),
                cursor.orderId(),
                2);

        assertThat(first).extracting(BuyerOrderView::orderId)
                .containsExactly(id(12), id(11));
        assertThat(second).extracting(BuyerOrderView::orderId)
                .containsExactly(id(10));
    }

    @Test
    void ownedDetailReturnsMultiBusinessImmutableSnapshots() {
        seedOrder(20, BUYER_ONE, BASE, "40.0000", 2);

        BuyerOrderView detail = repository.findOwnedDetail(BUYER_ONE, id(20)).orElseThrow();

        assertThat(detail.status()).isEqualTo("CONFIRMED");
        assertThat(detail.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(detail.totalAmount()).isEqualByComparingTo("40.0000");
        assertThat(detail.groups()).hasSize(2);
        assertThat(detail.groups()).allSatisfy(group -> {
            assertThat(group.status()).isEqualTo("PENDING_ACCEPTANCE");
            assertThat(group.items()).singleElement().satisfies(item -> {
                assertThat(item.title()).startsWith("Persisted item");
                assertThat(item.policyVersion()).isEqualTo("LOCAL_DEMO_V1");
                assertThat(item.lineTotal()).isEqualByComparingTo("20.0000");
            });
        });
        assertThat(detail.shippingAddress()).satisfies(address -> {
            assertThat(address.recipientName()).isEqualTo("Snapshot Buyer");
            assertThat(address.line1()).isEqualTo("20 Snapshot St");
            assertThat(address.countryCode()).isEqualTo("US");
        });
    }

    @Test
    void buyerFilterHidesOtherBuyerFromListAndDetail() {
        seedOrder(30, BUYER_ONE, BASE, "10.0000", 1);
        seedOrder(31, BUYER_TWO, BASE.plusSeconds(1), "11.0000", 1);

        assertThat(repository.findPage(BUYER_ONE, null, null, 10))
                .extracting(BuyerOrderView::orderId)
                .containsExactly(id(30));
        assertThat(repository.findOwnedDetail(BUYER_ONE, id(31))).isEmpty();
        assertThat(repository.findOwnedDetail(BUYER_ONE, id(999))).isEmpty();
    }

    private void seedOrder(
            int value,
            String buyerId,
            Instant createdAt,
            String total,
            int groupCount) {
        String checkoutId = id(1000 + value);
        String orderId = id(value);
        BigDecimal totalAmount = new BigDecimal(total);
        BigDecimal groupAmount = totalAmount.divide(BigDecimal.valueOf(groupCount));
        jdbc.update("""
                        INSERT INTO checkout_sessions (
                            id, buyer_id, status, version, cart_version, cart_snapshot_hash,
                            currency, subtotal, shipping, tax, discount, total, expires_at,
                            release_status, created_at, updated_at
                        ) VALUES (?, ?, 'COMPLETED', 2, 1, ?, 'USD', ?, 0, 0, 0, ?,
                                  ?, 'COMPLETE', ?, ?)
                        """,
                checkoutId,
                buyerId,
                "a".repeat(64),
                totalAmount,
                totalAmount,
                Timestamp.from(createdAt.plusSeconds(900)),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        jdbc.update("""
                        INSERT INTO orders (
                            id, checkout_id, payment_intent_id, buyer_id, order_number,
                            currency, subtotal, shipping, tax, discount, total,
                            payment_status, status, confirmed_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'USD', ?, 0, 0, 0, ?,
                                  'SUCCEEDED', 'CONFIRMED', ?, ?, ?)
                        """,
                orderId,
                checkoutId,
                id(2000 + value),
                buyerId,
                orderId,
                totalAmount,
                totalAmount,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));

        for (int group = 0; group < groupCount; group++) {
            String businessOrderId = id(3000 + value * 10 + group);
            String businessId = id(4000 + value * 10 + group);
            String storeId = id(5000 + value * 10 + group);
            jdbc.update("""
                            INSERT INTO business_orders (
                                id, order_id, business_id, store_id, seller_order_number,
                                fulfillment_status, cancellation_status, subtotal, shipping,
                                tax, discount, total, platform_fee_projection, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, 'PENDING_ACCEPTANCE', 'NONE',
                                      ?, 0, 0, 0, ?, NULL, ?, ?)
                            """,
                    businessOrderId,
                    orderId,
                    businessId,
                    storeId,
                    businessOrderId,
                    groupAmount,
                    groupAmount,
                    Timestamp.from(createdAt.plusMillis(group)),
                    Timestamp.from(createdAt.plusMillis(group)));
            jdbc.update("""
                            INSERT INTO order_items (
                                id, order_id, business_order_id, line_number, listing_id,
                                business_id, store_id, catalog_version, title, sku,
                                item_condition, thumbnail_url, quantity, unit_price, currency,
                                line_subtotal, shipping_allocation, tax_allocation,
                                discount_allocation, line_total, policy_version, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 7, ?, NULL, 'NEW', NULL,
                                      1, ?, 'USD', ?, 0, 0, 0, ?, 'LOCAL_DEMO_V1', ?)
                            """,
                    id(6000 + value * 10 + group),
                    orderId,
                    businessOrderId,
                    group + 1,
                    id(7000 + value * 10 + group),
                    businessId,
                    storeId,
                    "Persisted item " + group,
                    groupAmount,
                    groupAmount,
                    groupAmount,
                    Timestamp.from(createdAt));
        }
        jdbc.update("""
                        INSERT INTO order_addresses (
                            order_id, address_type, source_address_id, source_version, label,
                            recipient_name, phone, line1, line2, city, region, postal_code,
                            country_code, snapshotted_at
                        ) VALUES (?, 'SHIPPING', ?, 3, 'Home', 'Snapshot Buyer',
                                  '+15550123456', '20 Snapshot St', NULL, 'Irvine',
                                  'CA', '92618', 'US', ?)
                        """,
                orderId,
                id(8000 + value),
                Timestamp.from(createdAt));
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
