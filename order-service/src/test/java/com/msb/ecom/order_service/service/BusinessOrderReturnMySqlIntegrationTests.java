package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderReturnProperties;
import com.msb.ecom.order_service.dto.CreateBusinessOrderReturnRequest;
import com.msb.ecom.order_service.dto.ReceiveBusinessOrderReturnRequest;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.repository.BusinessOrderReturnRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessOrderReturnMySqlIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final String BUYER = id(1);
    private static final String ACTOR = id(2);
    private static final String ORDER = id(3);
    private static final String GROUP_A = id(4);
    private static final String GROUP_B = id(5);
    private static final String BUSINESS_A = "01KXQBUSI00000000000000001";
    private static final String BUSINESS_B = id(7);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders").withUsername("orders").withPassword("orders");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static BusinessOrderReturnRepository repository;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        dataSource = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new BusinessOrderReturnRepository(jdbc);
    }

    @AfterAll static void stop() { MYSQL.stop(); }

    @BeforeEach
    void cleanAndSeed() {
        jdbc.update("DELETE FROM business_order_return_history");
        jdbc.update("DELETE FROM business_order_return_shipments");
        jdbc.update("DELETE FROM business_order_return_commands");
        jdbc.update("DELETE FROM business_order_returns");
        jdbc.update("DELETE FROM shipment_status_history");
        jdbc.update("DELETE FROM shipments");
        jdbc.update("DELETE FROM order_outbox_events");
        jdbc.update("DELETE FROM business_order_status_history");
        jdbc.update("DELETE FROM business_order_acceptance_commands");
        jdbc.update("DELETE FROM order_status_history");
        jdbc.update("DELETE FROM order_addresses");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM business_orders");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM checkout_sessions");
        seed();
    }

    @Test
    void wholeGroupLifecycleIsReplaySafeAndLeavesSiblingAndFulfillmentUntouched() {
        var service = service();
        var requested = service.request(ORDER, GROUP_A, "4", "buyer-return-key-1",
                new CreateBusinessOrderReturnRequest("NOT_AS_EXPECTED", "Whole group"), "corr-request");
        var replay = service.request(ORDER, GROUP_A, "4", "buyer-return-key-1",
                new CreateBusinessOrderReturnRequest("NOT_AS_EXPECTED", "Whole group"), "corr-replay");
        assertThat(replay.returnId()).isEqualTo(requested.returnId());
        assertThat(requested.status()).isEqualTo("RETURN_REQUESTED");

        var inTransit = service.authorize(BUSINESS_A, GROUP_A, requested.returnId(), "0",
                "seller-authorize-key-1", "corr-authorize");
        assertThat(inTransit.status()).isEqualTo("RETURN_IN_TRANSIT");
        assertThat(inTransit.shipment().disclosure()).contains("No real shipment");

        var received = service.receive(BUSINESS_A, GROUP_A, requested.returnId(), "2",
                "seller-receive-key-1", new ReceiveBusinessOrderReturnRequest("RESTOCK_SELLABLE"),
                "corr-receive");
        assertThat(received.status()).isEqualTo("RETURN_RECEIVED");
        assertThat(received.refundStatus()).isEqualTo("PENDING");
        assertThat(received.inventoryDisposition()).isEqualTo("RESTOCK_SELLABLE");

        assertThat(count("business_order_returns")).isEqualTo(1);
        assertThat(count("business_order_return_shipments")).isEqualTo(1);
        assertThat(count("business_order_return_history")).isEqualTo(4);
        assertThat(count("business_order_return_commands")).isEqualTo(3);
        assertThat(count("order_outbox_events")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM business_orders WHERE id=?",
                String.class, GROUP_A)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT version FROM business_orders WHERE id=?",
                Long.class, GROUP_A)).isEqualTo(4L);
        assertThat(repository.findByGroup(GROUP_B)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM business_orders WHERE id=?",
                String.class, GROUP_B)).isEqualTo("DELIVERED");
    }

    @Test
    void rejectsExpiredWindowAndChangedIdempotentPayloadWithoutSideEffects() {
        jdbc.update("UPDATE shipments SET shipped_at=?,delivered_at=? WHERE business_order_id=?",
                Timestamp.from(NOW.minus(Duration.ofDays(32))),
                Timestamp.from(NOW.minus(Duration.ofDays(31))), GROUP_A);
        var service = service();
        assertThatThrownBy(() -> service.request(ORDER, GROUP_A, "4", "buyer-return-key-2",
                new CreateBusinessOrderReturnRequest("DAMAGED", null), "corr"))
                .isInstanceOfSatisfying(BuyerOrderException.class,
                        error -> assertThat(error.code()).isEqualTo("RETURN_WINDOW_EXPIRED"));
        assertThat(count("business_order_returns")).isZero();
        assertThat(count("business_order_return_commands")).isZero();

        jdbc.update("UPDATE shipments SET shipped_at=?,delivered_at=? WHERE business_order_id=?",
                Timestamp.from(NOW.minus(Duration.ofDays(2))),
                Timestamp.from(NOW.minus(Duration.ofDays(1))), GROUP_A);
        service.request(ORDER, GROUP_A, "4", "buyer-return-key-3",
                new CreateBusinessOrderReturnRequest("DAMAGED", null), "corr");
        assertThatThrownBy(() -> service.request(ORDER, GROUP_A, "4", "buyer-return-key-3",
                new CreateBusinessOrderReturnRequest("WRONG_ITEM", null), "corr"))
                .isInstanceOfSatisfying(BuyerOrderException.class,
                        error -> assertThat(error.code()).isEqualTo("RETURN_IDEMPOTENCY_CONFLICT"));
        assertThat(count("business_order_returns")).isEqualTo(1);
        assertThat(count("business_order_return_commands")).isEqualTo(1);
    }

    @Test
    void databaseRejectsImpossibleCompletedReturnWithoutReceiptAndRefundEvidence() {
        var requested = service().request(ORDER, GROUP_A, "4", "buyer-return-invariant",
                new CreateBusinessOrderReturnRequest("DAMAGED", null), "corr-invariant");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE business_order_returns
                SET status='RETURN_COMPLETED', refund_status='SUCCEEDED', completed_at=?
                WHERE id=?
                """, Timestamp.from(NOW), requested.returnId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_business_order_return_completion_fields");
    }

    private BusinessOrderReturnService service() {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        when(actors.currentActor()).thenReturn(new CurrentActor("buyer-sub", "seller-token", null, null, true));
        BuyerIdentityClient buyers = mock(BuyerIdentityClient.class);
        when(buyers.resolveBuyer("buyer-sub")).thenReturn(BUYER);
        BusinessOrderAuthorizationClient businesses = mock(BusinessOrderAuthorizationClient.class);
        when(businesses.authorizeFulfillment("seller-token", BUSINESS_A))
                .thenReturn(new BusinessOrderAuthorizationClient.Access(BUSINESS_A, ACTOR, "OWNER", true));
        return new BusinessOrderReturnService(
                new BusinessOrderReturnProperties(true, true, Duration.ofDays(30), Duration.ofSeconds(2), 20),
                actors, buyers, businesses, repository,
                new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)),
                new ObjectMapper().findAndRegisterModules(), new DataSourceTransactionManager(dataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seed() {
        String checkout = id(20);
        jdbc.update("""
                INSERT INTO checkout_sessions (id,buyer_id,status,version,cart_version,cart_snapshot_hash,
                  currency,subtotal,shipping,tax,discount,total,expires_at,release_status,created_at,updated_at)
                VALUES (?,?,'COMPLETED',2,1,?,'USD',40,0,0,0,40,?,'COMPLETE',?,?)
                """, checkout, BUYER, "a".repeat(64), Timestamp.from(NOW.plusSeconds(900)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO orders (id,checkout_id,payment_intent_id,buyer_id,order_number,currency,
                  subtotal,shipping,tax,discount,total,payment_status,status,confirmed_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'USD',40,0,0,0,40,'SUCCEEDED','CONFIRMED',?,?,?)
                """, ORDER, checkout, id(21), BUYER, id(22), Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        group(GROUP_A, BUSINESS_A, 20, 1);
        group(GROUP_B, BUSINESS_B, 20, 2);
        shipment(id(30), GROUP_A, BUSINESS_A, "RETURN-TEST-A");
        shipment(id(31), GROUP_B, BUSINESS_B, "RETURN-TEST-B");
    }

    private void group(String group, String business, int subtotal, int suffix) {
        jdbc.update("""
                INSERT INTO business_orders (id,order_id,business_id,store_id,seller_order_number,
                  fulfillment_status,cancellation_status,subtotal,shipping,tax,discount,total,
                  platform_fee_projection,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'DELIVERED','NONE',?,0,0,0,?,NULL,4,?,?)
                """, group, ORDER, business, id(60 + suffix), id(70 + suffix), subtotal, subtotal,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void shipment(String shipment, String group, String business, String tracking) {
        jdbc.update("""
                INSERT INTO shipments (id,business_order_id,business_id,source,carrier_display_name,
                  service_display_name,tracking_number,status,version,shipped_at,delivered_at,created_at,updated_at)
                VALUES (?,?,?,'LOCAL_DEMO_MANUAL','Demo Carrier','Ground',?,'DELIVERED',1,?,?,?,?)
                """, shipment, group, business, tracking, Timestamp.from(NOW.minus(Duration.ofDays(2))),
                Timestamp.from(NOW.minus(Duration.ofDays(1))), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static String id(int value) { return "01" + String.format("%024d", value); }
}
