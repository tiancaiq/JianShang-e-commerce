package com.msb.ecom.order_service.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.time.Instant;

import static com.msb.ecom.order_service.analytics.OrderAnalyticsContracts.Granularity;
import static com.msb.ecom.order_service.analytics.OrderAnalyticsContracts.TrendMetric;
import static org.assertj.core.api.Assertions.assertThat;

class OrderAnalyticsRepositoryMySqlTests {
    private static final Instant FROM = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-23T00:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("order_analytics").withUsername("orders").withPassword("orders");

    private static JdbcTemplate jdbc;
    private static OrderAnalyticsRepository repository;

    @BeforeAll
    static void migrateAndSeed() {
        MYSQL.start();
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new OrderAnalyticsRepository(jdbc);
        seed();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void exactAggregatesUseHalfOpenEventWindowsAndCurrentStateBacklog() {
        var orders = repository.orders(FROM, TO, true);
        assertThat(orders.ordersCreated()).isEqualTo(3);
        assertThat(orders.ordersConfirmed()).isEqualTo(4);
        assertThat(orders.currentCancelledOrders()).isEqualTo(1);
        assertThat(orders.fullyDeliveredOrders()).isEqualTo(1);
        assertThat(orders.grossCreatedAmounts()).singleElement().satisfies(amount -> {
            assertThat(amount.currency()).isEqualTo("USD");
            assertThat(amount.amount()).isEqualByComparingTo("60.0000");
        });
        assertThat(repository.orders(FROM, TO, false).grossCreatedAmounts()).isNull();

        var disputes = repository.disputes(FROM, TO);
        assertThat(disputes.disputesOpened()).isEqualTo(3);
        assertThat(disputes.disputesResolved()).isEqualTo(2);
        assertThat(disputes.resolvedNoAction()).isEqualTo(1);
        assertThat(disputes.returnsApproved()).isEqualTo(1);
        assertThat(disputes.refundsRecommended()).isZero();

        var backlog = repository.disputeBacklog();
        assertThat(backlog.openCount()).isEqualTo(2);
        assertThat(backlog.unassignedCount()).isEqualTo(1);
        assertThat(backlog.oldestOpenCreatedAt()).isEqualTo(FROM);

        assertThat(repository.trend(TrendMetric.ORDERS_CREATED, Granularity.DAY, FROM, TO))
                .extracting(OrderAnalyticsContracts.TrendPoint::value).containsExactly(2L, 1L);
        assertThat(repository.trend(TrendMetric.DISPUTES_OPENED, Granularity.DAY, FROM, TO))
                .extracting(OrderAnalyticsContracts.TrendPoint::value).containsExactly(3L);
    }

    private static void seed() {
        Seed first = order(1, FROM, FROM, "CONFIRMED", "10.0000", "DELIVERED", "NONE");
        businessOrder(57, first.orderId(), "PENDING_ACCEPTANCE", "CANCELLED", FROM);
        Seed cancelled = order(2, TO.minusNanos(1_000), TO.minusSeconds(1), "CANCELLED", "20.0000",
                "PENDING_ACCEPTANCE", "CANCELLED");
        order(3, TO, TO, "CONFIRMED", "30.0000", "DELIVERED", "NONE");
        order(4, FROM.minusSeconds(1), FROM.plusSeconds(1), "CONFIRMED", "40.0000",
                "DELIVERED", "NONE");
        Seed incomplete = order(5, FROM.plusSeconds(2), FROM.plusSeconds(2), "CONFIRMED", "30.0000",
                "DELIVERED", "NONE");
        businessOrder(56, incomplete.orderId(), "SHIPPED", "NONE", FROM.plusSeconds(2));

        dispute(101, first, "OPEN", null, FROM, null, null);
        dispute(102, incomplete, "UNDER_ADMIN_REVIEW", id(900), FROM.plusSeconds(1), null, null);
        dispute(103, first, "RESOLVED_NO_ACTION", id(901), FROM.plusSeconds(2),
                FROM.plusSeconds(3), null);
        dispute(104, cancelled, "REFUND_RECOMMENDED", id(901), TO, TO, "5.0000");
        dispute(105, cancelled, "RETURN_APPROVED", id(901), FROM.minusSeconds(10),
                FROM.plusSeconds(4), null);
    }

    private static Seed order(int value, Instant createdAt, Instant confirmedAt, String status,
                              String total, String fulfillment, String cancellation) {
        String checkoutId = id(1000 + value);
        String orderId = id(2000 + value);
        jdbc.update("""
                insert into checkout_sessions (
                    id,buyer_id,status,version,cart_version,cart_snapshot_hash,currency,
                    subtotal,shipping,tax,discount,total,expires_at,release_status,created_at,updated_at
                ) values (?,?,'COMPLETED',2,1,?,'USD',?,0,0,0,?,?,'COMPLETE',?,?)
                """, checkoutId, id(3000 + value), "a".repeat(64), total, total,
                ts(createdAt.plusSeconds(3600)), ts(createdAt), ts(createdAt));
        jdbc.update("""
                insert into orders (
                    id,checkout_id,payment_intent_id,buyer_id,order_number,currency,
                    subtotal,shipping,tax,discount,total,payment_status,status,confirmed_at,created_at,updated_at
                ) values (?,?,?,?,?,'USD',?,0,0,0,?,'SUCCEEDED',?,?,?,?)
                """, orderId, checkoutId, id(4000 + value), id(3000 + value), id(5000 + value),
                total, total, status, ts(confirmedAt), ts(createdAt), ts(createdAt));
        String businessOrderId = businessOrder(50 + value, orderId, fulfillment, cancellation, createdAt);
        return new Seed(orderId, businessOrderId);
    }

    private static String businessOrder(int value, String orderId, String fulfillment,
                                        String cancellation, Instant createdAt) {
        String groupId = id(6000 + value);
        jdbc.update("""
                insert into business_orders (
                    id,order_id,business_id,store_id,seller_order_number,fulfillment_status,
                    cancellation_status,subtotal,shipping,tax,discount,total,platform_fee_projection,
                    created_at,updated_at
                ) values (?,?,?,?,?,?,?,10,0,0,0,10,null,?,?)
                """, groupId, orderId, id(7000 + value), id(8000 + value), id(9000 + value),
                fulfillment, cancellation, ts(createdAt), ts(createdAt));
        return groupId;
    }

    private static void dispute(int value, Seed seed, String status, String assignedAdmin,
                                Instant createdAt, Instant resolvedAt, String refundAmount) {
        boolean resolved = resolvedAt != null;
        jdbc.update("""
                insert into order_disputes (
                    id,order_id,business_order_id,business_id,buyer_user_id,opened_by_type,
                    opened_by_user_id,reason_code,description,status,priority,assigned_admin_id,
                    resolution_type,resolution_reason_code,resolution_reason,recommended_refund_amount,
                    currency,created_at,updated_at,resolved_at,correlation_id
                ) values (?,?,?,?,?,'BUYER',?,'OTHER','Analytics fixture',?,'MEDIUM',?,?,?,?,?,
                          'USD',?,?,?,?)
                """, id(10000 + value), seed.orderId(), seed.businessOrderId(), id(7000 + 50 + value % 5),
                id(11000 + value), id(11000 + value), status, assignedAdmin,
                resolved ? status : null, resolved ? "ANALYTICS_TEST" : null,
                resolved ? "Resolved for aggregate test" : null, refundAmount,
                ts(createdAt), ts(createdAt), resolvedAt == null ? null : ts(resolvedAt), "analytics-" + value);
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static String id(int value) { return "01" + String.format("%024d", value); }
    private record Seed(String orderId, String businessOrderId) { }
}
