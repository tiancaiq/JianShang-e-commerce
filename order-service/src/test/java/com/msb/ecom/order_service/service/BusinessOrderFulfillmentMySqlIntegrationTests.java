package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderFulfillmentProperties;
import com.msb.ecom.order_service.dto.CreateManualShipmentRequest;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.repository.BusinessOrderFulfillmentRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessOrderFulfillmentMySqlIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-08-03T15:00:00Z");
    private static final String BUSINESS_A = "01KXQBUSI00000000000000001";
    private static final String BUSINESS_B = id(2);
    private static final String GROUP_A = id(3);
    private static final String GROUP_B = id(4);
    private static final String ACTOR = id(5);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static BusinessOrderFulfillmentRepository repository;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        String jdbcUrl = MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        dataSource = new DriverManagerDataSource(
                jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new BusinessOrderFulfillmentRepository(jdbc);
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM shipment_status_history");
        jdbc.update("DELETE FROM shipments");
        jdbc.update("DELETE FROM business_order_fulfillment_commands");
        jdbc.update("DELETE FROM business_order_status_history");
        jdbc.update("DELETE FROM order_outbox_events");
        jdbc.update("DELETE FROM business_order_acceptance_commands");
        jdbc.update("DELETE FROM order_status_history");
        jdbc.update("DELETE FROM order_addresses");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM business_orders");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM checkout_sessions");
    }

    @Test
    void v8CreatesSingleShipmentConstraintAndAuthoritativeStatuses() {
        assertThat(count("shipments")).isZero();
        String clause = jdbc.queryForObject("""
                SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND CONSTRAINT_NAME = 'chk_business_order_fulfillment'
                """, String.class);
        assertThat(clause).contains(
                "PENDING_ACCEPTANCE", "ACCEPTED", "PROCESSING", "SHIPPED", "DELIVERED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shipments'
                  AND INDEX_NAME = 'uk_shipment_business_order' AND NON_UNIQUE = 0
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void processingShipmentAndDemoDeliveryAreAtomicAndReplaySafe() {
        seed("ACCEPTED", 1, "PENDING_ACCEPTANCE", 0);
        BusinessOrderFulfillmentService service = service();

        var processing = service.startProcessing(
                BUSINESS_A, GROUP_A, "1", "process-key-001", "c1");
        var processingReplay = service.startProcessing(
                BUSINESS_A, GROUP_A, "\"1\"", "process-key-001", "c2");
        assertThat(processingReplay).isEqualTo(processing);
        assertThat(processing.fulfillmentStatus()).isEqualTo("PROCESSING");

        CreateManualShipmentRequest request = new CreateManualShipmentRequest(
                "Demo Carrier", "Ground", "DEMO-GROUP-A", NOW);
        var shipped = service.createShipment(
                BUSINESS_A, GROUP_A, "2", "shipment-key-001", request, "c3");
        var shipmentReplay = service.createShipment(
                BUSINESS_A, GROUP_A, "\"2\"", "shipment-key-001", request, "c4");
        assertThat(shipmentReplay).isEqualTo(shipped);
        assertThat(shipped.shipment()).satisfies(value -> {
            assertThat(value.source()).isEqualTo("LOCAL_DEMO_MANUAL");
            assertThat(value.status()).isEqualTo("SHIPPED");
            assertThat(value.trackingNumber()).isEqualTo("DEMO-GROUP-A");
        });

        var delivered = service.recordDemoDelivery(
                BUSINESS_A, GROUP_A, "3", "delivery-key-001", "c5");
        assertThat(delivered.fulfillmentStatus()).isEqualTo("DELIVERED");
        assertThat(delivered.shipment().status()).isEqualTo("DELIVERED");
        assertThat(delivered.shipment().deliveredAt()).isEqualTo(NOW);

        assertThat(service.startProcessing(
                BUSINESS_A, GROUP_A, "1", "process-key-001", "c6"))
                .isEqualTo(processing);
        assertThat(service.createShipment(
                BUSINESS_A, GROUP_A, "2", "shipment-key-001", request, "c7"))
                .isEqualTo(shipped);

        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("shipment_status_history")).isEqualTo(2);
        assertThat(count("business_order_status_history")).isEqualTo(3);
        assertThat(count("order_outbox_events")).isEqualTo(3);
        assertThat(count("business_order_fulfillment_commands")).isEqualTo(3);
        assertThat(status(GROUP_B)).isEqualTo("PENDING_ACCEPTANCE");
        assertThat(version(GROUP_B)).isZero();
    }

    @Test
    void invalidStateStaleVersionAndCrossBusinessFailSafely() {
        seed("PENDING_ACCEPTANCE", 0, "PENDING_ACCEPTANCE", 0);
        BusinessOrderFulfillmentService service = service();

        assertCode(() -> service.startProcessing(
                BUSINESS_A, GROUP_A, "0", "process-key-001", "c"),
                "BUSINESS_ORDER_STATE_CONFLICT");
        assertCode(() -> service.startProcessing(
                BUSINESS_B, GROUP_A, "0", "process-key-002", "c"),
                "BUSINESS_ORDER_NOT_FOUND");

        jdbc.update("UPDATE business_orders SET fulfillment_status='ACCEPTED', version=1 WHERE id=?",
                GROUP_A);
        assertCode(() -> service.startProcessing(
                BUSINESS_A, GROUP_A, "0", "process-key-003", "c"),
                "BUSINESS_ORDER_VERSION_CONFLICT");
        assertThat(count("business_order_fulfillment_commands")).isZero();
        assertThat(count("business_order_status_history")).isZero();
        assertThat(count("order_outbox_events")).isZero();
    }

    @Test
    void concurrentIdenticalShipmentCreatesOneRecordAndOneHistory() throws Exception {
        seed("PROCESSING", 2, "PENDING_ACCEPTANCE", 0);
        BusinessOrderFulfillmentService service = service();
        CreateManualShipmentRequest request = new CreateManualShipmentRequest(
                "Demo Carrier", "Ground", "DEMO-CONCURRENT", NOW);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.createShipment(
                    BUSINESS_A, GROUP_A, "2", "shipment-same-001", request, "c1"));
            var second = executor.submit(() -> service.createShipment(
                    BUSINESS_A, GROUP_A, "2", "shipment-same-001", request, "c2"));
            String firstId = first.get(20, TimeUnit.SECONDS).shipment().shipmentId();
            String secondId = second.get(20, TimeUnit.SECONDS).shipment().shipmentId();
            assertThat(secondId).isEqualTo(firstId);
        }

        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("shipment_status_history")).isEqualTo(1);
        assertThat(count("business_order_status_history")).isEqualTo(1);
        assertThat(count("order_outbox_events")).isEqualTo(1);
    }

    @Test
    void distinctConcurrentShipmentKeysAllowOneSuccessAndOneConflict() throws Exception {
        seed("PROCESSING", 2, "PENDING_ACCEPTANCE", 0);
        BusinessOrderFulfillmentService service = service();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> shipmentOutcome(service, "shipment-a-001"));
            var second = executor.submit(() -> shipmentOutcome(service, "shipment-b-001"));
            assertThat(Set.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .contains("SHIPPED", "BUSINESS_ORDER_VERSION_CONFLICT");
        }
        assertThat(count("shipments")).isEqualTo(1);
        assertThat(count("business_order_fulfillment_commands")).isEqualTo(1);
    }

    private BusinessOrderFulfillmentService service() {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        when(actors.currentActor()).thenReturn(
                new CurrentActor("subject", "actor-token", null, null, true));
        BusinessOrderAuthorizationClient authorization = mock(BusinessOrderAuthorizationClient.class);
        when(authorization.authorizeFulfillment("actor-token", BUSINESS_A))
                .thenReturn(new BusinessOrderAuthorizationClient.Access(
                        BUSINESS_A, ACTOR, "OWNER", true));
        when(authorization.authorizeFulfillment("actor-token", BUSINESS_B))
                .thenReturn(new BusinessOrderAuthorizationClient.Access(
                        BUSINESS_B, ACTOR, "OWNER", true));
        return new BusinessOrderFulfillmentService(
                new BusinessOrderFulfillmentProperties(
                        true, true, true, Duration.ofDays(7), 50),
                actors, authorization, repository,
                new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)),
                new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seed(String groupAStatus, long groupAVersion, String groupBStatus, long groupBVersion) {
        String checkoutId = id(10);
        String orderId = id(11);
        jdbc.update("""
                INSERT INTO checkout_sessions (
                    id, buyer_id, status, version, cart_version, cart_snapshot_hash,
                    currency, subtotal, shipping, tax, discount, total, expires_at,
                    release_status, created_at, updated_at
                ) VALUES (?, ?, 'COMPLETED', 2, 1, ?, 'USD', 40, 0, 0, 0, 40,
                          ?, 'COMPLETE', ?, ?)
                """, checkoutId, id(12), "a".repeat(64), Timestamp.from(NOW.plusSeconds(900)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO orders (
                    id, checkout_id, payment_intent_id, buyer_id, order_number,
                    currency, subtotal, shipping, tax, discount, total,
                    payment_status, status, confirmed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'USD', 40, 0, 0, 0, 40,
                          'SUCCEEDED', 'CONFIRMED', ?, ?, ?)
                """, orderId, checkoutId, id(13), id(12), id(14), Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));
        insertGroup(GROUP_A, orderId, BUSINESS_A, groupAStatus, groupAVersion, 20);
        insertGroup(GROUP_B, orderId, BUSINESS_B, groupBStatus, groupBVersion, 21);
    }

    private void insertGroup(
            String groupId, String orderId, String businessId, String status,
            long version, int suffix) {
        jdbc.update("""
                INSERT INTO business_orders (
                    id, order_id, business_id, store_id, seller_order_number,
                    fulfillment_status, cancellation_status, subtotal, shipping,
                    tax, discount, total, platform_fee_projection, version,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'NONE', 20, 0, 0, 0, 20,
                          NULL, ?, ?, ?)
                """, groupId, orderId, businessId, id(100 + suffix), id(200 + suffix),
                status, version, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private String shipmentOutcome(BusinessOrderFulfillmentService service, String key) {
        try {
            return service.createShipment(
                    BUSINESS_A, GROUP_A, "2", key,
                    new CreateManualShipmentRequest(
                            "Demo Carrier", "Ground", "DEMO-" + key, NOW),
                    "c").fulfillmentStatus();
        } catch (BusinessOrderException exception) {
            return exception.code();
        }
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                BusinessOrderException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private String status(String groupId) {
        return jdbc.queryForObject(
                "SELECT fulfillment_status FROM business_orders WHERE id=?",
                String.class, groupId);
    }

    private long version(String groupId) {
        return jdbc.queryForObject(
                "SELECT version FROM business_orders WHERE id=?", Long.class, groupId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
