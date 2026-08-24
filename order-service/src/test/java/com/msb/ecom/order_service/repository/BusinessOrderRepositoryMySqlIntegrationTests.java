package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.dto.OrderDisputeContracts;
import com.msb.ecom.order_service.model.BusinessOrderView;
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

class BusinessOrderRepositoryMySqlIntegrationTests {

    private static final String BUSINESS_ONE = id(900);
    private static final String BUSINESS_TWO = id(901);
    private static final Instant BASE = Instant.parse("2026-07-20T01:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    private static JdbcTemplate jdbc;
    private static BusinessOrderRepository repository;
    private static AdminOrderRepository adminOrderRepository;
    private static OrderDisputeRepository disputeRepository;

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
        repository = new BusinessOrderRepository(jdbc);
        adminOrderRepository = new AdminOrderRepository(jdbc);
        disputeRepository = new OrderDisputeRepository(jdbc, new ObjectMapper());
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM order_dispute_commands");
        jdbc.update("DELETE FROM order_dispute_events");
        jdbc.update("DELETE FROM order_dispute_admin_notes");
        jdbc.update("DELETE FROM order_dispute_evidence");
        jdbc.update("DELETE FROM order_dispute_statements");
        jdbc.update("DELETE FROM order_dispute_items");
        jdbc.update("DELETE FROM order_disputes");
        jdbc.update("DELETE FROM order_addresses");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM business_orders");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM checkout_sessions");
    }

    @Test
    void disputeResolutionPersistsARecommendationWithoutMutatingPaymentOrOrderState() {
        Seed seed = seedOrder(2, BASE, BUSINESS_ONE);
        jdbc.update("UPDATE business_orders SET fulfillment_status='DELIVERED' WHERE id=?",
                seed.firstBusinessOrderId());
        String disputeId = id(12002);
        String itemId = id(8020);
        var row = new OrderDisputeRepository.DisputeRow(disputeId, seed.orderId(),
                seed.firstBusinessOrderId(), BUSINESS_ONE, id(2002), "BUYER", id(2002),
                "ITEM_NOT_AS_DESCRIBED", "The received item differs from its purchase snapshot.",
                OrderDisputeContracts.Status.OPEN, OrderDisputeContracts.Priority.MEDIUM, null,
                null, null, null, null, "USD", null, null, BASE, BASE, null, 0,
                "dispute-test-correlation");

        disputeRepository.insert(row, List.of(itemId));
        var context = disputeRepository.scopeForDispute(disputeId, false).orElseThrow();
        assertThat(context.releaseStatus()).isEqualTo("COMPLETE");
        assertThat(context.shipmentStatus()).isNull();
        assertThat(disputeRepository.claim(disputeId, 0, id(13002), BASE.plusSeconds(1))).isOne();
        assertThat(disputeRepository.status(disputeId, 1, id(13002),
                OrderDisputeContracts.Status.UNDER_ADMIN_REVIEW,
                OrderDisputeContracts.Status.READY_FOR_DECISION, BASE.plusSeconds(2))).isOne();
        assertThat(disputeRepository.resolve(disputeId, 2, id(13002),
                OrderDisputeContracts.Resolution.PARTIAL_REFUND_RECOMMENDED,
                "ITEM_VALUE_ADJUSTMENT", "Recommend a bounded adjustment.",
                new BigDecimal("5.0000"), null, null, BASE.plusSeconds(3))).isOne();

        var resolved = disputeRepository.find(disputeId, false).orElseThrow();
        assertThat(resolved.status()).isEqualTo(
                OrderDisputeContracts.Status.PARTIAL_REFUND_RECOMMENDED);
        assertThat(resolved.recommendedRefundAmount()).isEqualByComparingTo("5.0000");
        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id=?", String.class,
                seed.orderId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class,
                seed.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_order_returns WHERE order_id=?",
                Integer.class, seed.orderId())).isZero();
        var followUp = new OrderDisputeRepository.DisputeRow(id(12003), seed.orderId(),
                seed.firstBusinessOrderId(), BUSINESS_ONE, id(2002), "BUYER", id(2002),
                "RETURN_DISAGREEMENT", "A distinct issue occurred after the first decision.",
                OrderDisputeContracts.Status.OPEN, OrderDisputeContracts.Priority.MEDIUM, null,
                null, null, null, null, "USD", null, null, BASE.plusSeconds(4),
                BASE.plusSeconds(4), null, 0, "follow-up-correlation");
        disputeRepository.insert(followUp, List.of(itemId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_disputes WHERE business_order_id=?",
                Integer.class, seed.firstBusinessOrderId())).isEqualTo(2);
    }

    @Test
    void sqlBusinessPredicateHidesSiblingGroupsFromQueueAndDetail() {
        Seed seed = seedOrder(1, BASE, BUSINESS_ONE, BUSINESS_TWO);

        assertThat(repository.findPage(BUSINESS_ONE, null, null, null, 10, false))
                .extracting(BusinessOrderView::businessOrderId)
                .containsExactly(seed.firstBusinessOrderId());
        assertThat(repository.findOwnedDetail(
                BUSINESS_ONE, seed.secondBusinessOrderId(), false)).isEmpty();
        assertThat(repository.findOwnedDetail(
                BUSINESS_ONE, id(999), false)).isEmpty();
    }

    @Test
    void cursorRemainsStableWhenANewerBusinessOrderIsInserted() {
        Seed first = seedOrder(10, BASE.plusSeconds(30), BUSINESS_ONE);
        Seed second = seedOrder(11, BASE.plusSeconds(20), BUSINESS_ONE);
        Seed third = seedOrder(12, BASE.plusSeconds(10), BUSINESS_ONE);

        List<BusinessOrderView> page =
                repository.findPage(BUSINESS_ONE, null, null, null, 2, false);
        seedOrder(13, BASE.plusSeconds(40), BUSINESS_ONE);
        List<BusinessOrderView> next = repository.findPage(
                BUSINESS_ONE,
                null,
                page.get(1).createdAt(),
                page.get(1).businessOrderId(),
                2,
                false);

        assertThat(page).extracting(BusinessOrderView::businessOrderId)
                .containsExactly(first.firstBusinessOrderId(), second.firstBusinessOrderId());
        assertThat(next).extracting(BusinessOrderView::businessOrderId)
                .containsExactly(third.firstBusinessOrderId());
    }

    @Test
    void exactStatusFilterAndSameTimestampIdTieBreakerAreDeterministic() {
        Seed lower = seedOrder(20, BASE, BUSINESS_ONE);
        Seed higher = seedOrder(21, BASE, BUSINESS_ONE);

        List<BusinessOrderView> page = repository.findPage(
                BUSINESS_ONE, "PENDING_ACCEPTANCE", null, null, 10, false);

        assertThat(page).extracting(BusinessOrderView::businessOrderId)
                .containsExactly(higher.firstBusinessOrderId(), lower.firstBusinessOrderId());
        assertThat(repository.findPage(
                BUSINESS_ONE, "SHIPPED", null, null, 10, false)).isEmpty();
    }

    @Test
    void cancelledFilterIsDistinctFromPendingAcceptance() {
        Seed cancelled = seedOrder(22, BASE.plusSeconds(1), BUSINESS_ONE);
        Seed pending = seedOrder(23, BASE, BUSINESS_ONE);
        jdbc.update("UPDATE business_orders SET cancellation_status = 'CANCELLED' WHERE id = ?",
                cancelled.firstBusinessOrderId());

        assertThat(repository.findPage(
                BUSINESS_ONE, "PENDING_ACCEPTANCE", null, null, 10, false))
                .extracting(BusinessOrderView::businessOrderId)
                .containsExactly(pending.firstBusinessOrderId());
        assertThat(repository.findPage(
                BUSINESS_ONE, "CANCELLED", null, null, 10, false))
                .extracting(BusinessOrderView::businessOrderId)
                .containsExactly(cancelled.firstBusinessOrderId());
    }

    @Test
    void adminFulfillmentFilterMatchesTheCancellationAwareAggregateShownInQueueRows() {
        Seed cancelled = seedOrder(24, BASE.plusSeconds(3), BUSINESS_ONE);
        Seed mixed = seedOrder(25, BASE.plusSeconds(2), BUSINESS_ONE, BUSINESS_TWO);
        Seed accepted = seedOrder(26, BASE.plusSeconds(1), BUSINESS_ONE);
        jdbc.update("UPDATE business_orders SET cancellation_status = 'CANCELLED' WHERE order_id = ?",
                cancelled.orderId());
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled.orderId());
        jdbc.update("UPDATE business_orders SET fulfillment_status = 'ACCEPTED' WHERE id = ?",
                mixed.secondBusinessOrderId());
        jdbc.update("UPDATE business_orders SET fulfillment_status = 'ACCEPTED' WHERE order_id = ?",
                accepted.orderId());

        AdminOrderRepository.SearchResult pending = adminOrderRepository.search(
                filter("PENDING_ACCEPTANCE"));
        AdminOrderRepository.SearchResult acceptedResult = adminOrderRepository.search(filter("ACCEPTED"));

        assertThat(pending.rows())
                .extracting(AdminOrderRepository.SearchRow::orderId)
                .containsExactly(mixed.orderId());
        assertThat(pending.rows())
                .extracting(AdminOrderRepository.SearchRow::fulfillmentStatus)
                .containsOnly("PENDING_ACCEPTANCE");
        assertThat(acceptedResult.rows())
                .extracting(AdminOrderRepository.SearchRow::orderId)
                .containsExactly(accepted.orderId());
        assertThat(acceptedResult.rows())
                .extracting(AdminOrderRepository.SearchRow::fulfillmentStatus)
                .containsOnly("ACCEPTED");
    }

    @Test
    void detailContainsOnlyOwnedItemsAndImmutableMinimumAddress() {
        Seed seed = seedOrder(30, BASE, BUSINESS_ONE, BUSINESS_TWO);

        BusinessOrderView detail = repository.findOwnedDetail(
                BUSINESS_ONE, seed.firstBusinessOrderId(), false).orElseThrow();

        assertThat(detail.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(detail.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Persisted item 0");
            assertThat(item.policyVersion()).isEqualTo("LOCAL_DEMO_V1");
            assertThat(item.lineTotal()).isEqualByComparingTo("20.0000");
        });
        assertThat(detail.shippingAddress()).satisfies(address -> {
            assertThat(address.recipientName()).isEqualTo("Snapshot Buyer");
            assertThat(address.line1()).isEqualTo("30 Snapshot St");
            assertThat(address.countryCode()).isEqualTo("US");
        });
    }

    @Test
    void financeProjectionIsSelectedOnlyWhenAuthorized() {
        Seed seed = seedOrder(40, BASE, BUSINESS_ONE);
        jdbc.update(
                "ALTER TABLE business_orders DROP CHECK chk_business_order_amounts");
        jdbc.update(
                "UPDATE business_orders SET platform_fee_projection = 2.5000 WHERE id = ?",
                seed.firstBusinessOrderId());

        BusinessOrderView hidden = repository.findOwnedDetail(
                BUSINESS_ONE, seed.firstBusinessOrderId(), false).orElseThrow();
        BusinessOrderView visible = repository.findOwnedDetail(
                BUSINESS_ONE, seed.firstBusinessOrderId(), true).orElseThrow();

        assertThat(hidden.platformFeeProjection()).isNull();
        assertThat(visible.platformFeeProjection()).isEqualByComparingTo("2.5000");
    }

    @Test
    void queuePlansAvoidFilesortForFulfillmentCancellationAndUnfilteredQueues() {
        seedOrder(50, BASE, BUSINESS_ONE);

        List<String> unfilteredExtra = jdbc.query("""
                        EXPLAIN SELECT id
                        FROM business_orders FORCE INDEX (idx_business_order_all_queue)
                        WHERE business_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 21
                        """,
                (rs, rowNum) -> rs.getString("Extra"),
                BUSINESS_ONE);
        List<String> filteredExtra = jdbc.query("""
                        EXPLAIN SELECT id
                        FROM business_orders FORCE INDEX (idx_business_order_queue)
                        WHERE business_id = ? AND fulfillment_status = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 21
                        """,
                (rs, rowNum) -> rs.getString("Extra"),
                BUSINESS_ONE,
                "PENDING_ACCEPTANCE");
        List<String> cancelledExtra = jdbc.query("""
                        EXPLAIN SELECT id
                        FROM business_orders FORCE INDEX (idx_business_order_cancellation_queue)
                        WHERE business_id = ? AND cancellation_status = 'CANCELLED'
                        ORDER BY created_at DESC, id DESC
                        LIMIT 21
                        """,
                (rs, rowNum) -> rs.getString("Extra"),
                BUSINESS_ONE);

        assertThat(unfilteredExtra).noneMatch(extra ->
                extra != null && extra.contains("Using filesort"));
        assertThat(filteredExtra).noneMatch(extra ->
                extra != null && extra.contains("Using filesort"));
        assertThat(cancelledExtra).noneMatch(extra ->
                extra != null && extra.contains("Using filesort"));
    }

    private Seed seedOrder(int value, Instant createdAt, String... businesses) {
        String checkoutId = id(1000 + value);
        String orderId = id(value);
        BigDecimal orderTotal = BigDecimal.valueOf(20L * businesses.length).setScale(4);
        jdbc.update("""
                        INSERT INTO checkout_sessions (
                            id, buyer_id, status, version, cart_version, cart_snapshot_hash,
                            currency, subtotal, shipping, tax, discount, total, expires_at,
                            release_status, created_at, updated_at
                        ) VALUES (?, ?, 'COMPLETED', 2, 1, ?, 'USD', ?, 0, 0, 0, ?,
                                  ?, 'COMPLETE', ?, ?)
                        """,
                checkoutId,
                id(2000 + value),
                "a".repeat(64),
                orderTotal,
                orderTotal,
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
                id(3000 + value),
                id(2000 + value),
                id(4000 + value),
                orderTotal,
                orderTotal,
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));

        String first = null;
        String second = null;
        for (int index = 0; index < businesses.length; index++) {
            String businessOrderId = id(5000 + value * 10 + index);
            if (index == 0) {
                first = businessOrderId;
            } else if (index == 1) {
                second = businessOrderId;
            }
            jdbc.update("""
                            INSERT INTO business_orders (
                                id, order_id, business_id, store_id, seller_order_number,
                                fulfillment_status, cancellation_status, subtotal, shipping,
                                tax, discount, total, platform_fee_projection, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, 'PENDING_ACCEPTANCE', 'NONE',
                                      20, 0, 0, 0, 20, NULL, ?, ?)
                            """,
                    businessOrderId,
                    orderId,
                    businesses[index],
                    id(6000 + value * 10 + index),
                    id(7000 + value * 10 + index),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt));
            jdbc.update("""
                            INSERT INTO order_items (
                                id, order_id, business_order_id, line_number, listing_id,
                                business_id, store_id, catalog_version, title, sku,
                                item_condition, thumbnail_url, quantity, unit_price, currency,
                                line_subtotal, shipping_allocation, tax_allocation,
                                discount_allocation, line_total, policy_version, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 7, ?, 'SKU-1', 'NEW', NULL,
                                      2, 10, 'USD', 20, 0, 0, 0, 20, 'LOCAL_DEMO_V1', ?)
                            """,
                    id(8000 + value * 10 + index),
                    orderId,
                    businessOrderId,
                    index + 1,
                    id(9000 + value * 10 + index),
                    businesses[index],
                    id(6000 + value * 10 + index),
                    "Persisted item " + index,
                    Timestamp.from(createdAt));
        }
        jdbc.update("""
                        INSERT INTO order_addresses (
                            order_id, address_type, source_address_id, source_version, label,
                            recipient_name, phone, line1, line2, city, region, postal_code,
                            country_code, snapshotted_at
                        ) VALUES (?, 'SHIPPING', ?, 3, 'Home', 'Snapshot Buyer',
                                  '+15550123456', ?, NULL, 'Irvine',
                                  'CA', '92618', 'US', ?)
                        """,
                orderId,
                id(10000 + value),
                value + " Snapshot St",
                Timestamp.from(createdAt));
        return new Seed(orderId, first, second);
    }

    private AdminOrderRepository.SearchFilter filter(String fulfillmentStatus) {
        return new AdminOrderRepository.SearchFilter(null, null, null, null,
                null, null, fulfillmentStatus, null, null, 0, 25, "createdAt,desc");
    }

    private record Seed(String orderId, String firstBusinessOrderId, String secondBusinessOrderId) {
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
