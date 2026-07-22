package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderAcceptanceProperties;
import com.msb.ecom.order_service.dto.BusinessOrderAcceptanceResponse;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.repository.BusinessOrderAcceptanceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class BusinessOrderAcceptanceMySqlIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private static final String BUSINESS_ID = id(1);
    private static final String OTHER_BUSINESS_ID = id(2);
    private static final String BUSINESS_ORDER_ID = id(3);
    private static final String ACTOR_ID = id(4);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    private static JdbcTemplate jdbc;
    private static DriverManagerDataSource dataSource;
    private static BusinessOrderAcceptanceRepository repository;
    private static ObjectMapper objectMapper;

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
        repository = new BusinessOrderAcceptanceRepository(jdbc);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @BeforeEach
    void clean() {
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
    void v5MigratesExactVersionAndFulfillmentConstraints() {
        String versionClause = jdbc.queryForObject("""
                SELECT CHECK_CLAUSE
                FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND CONSTRAINT_NAME = 'chk_business_order_version'
                """, String.class);
        String fulfillmentClause = jdbc.queryForObject("""
                SELECT CHECK_CLAUSE
                FROM information_schema.CHECK_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE()
                  AND CONSTRAINT_NAME = 'chk_business_order_fulfillment'
                """, String.class);

        assertThat(versionClause).contains("version", ">= 0");
        assertThat(fulfillmentClause)
                .contains("PENDING_ACCEPTANCE", "ACCEPTED")
                .doesNotContain("SHIPPED", "DELIVERED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '5' AND success = 1
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void successAndReplayWriteExactlyOneTransitionHistoryAndOutbox() throws Exception {
        seed("SUCCEEDED", "PENDING_ACCEPTANCE", 0);
        BusinessOrderAcceptanceService service = service(repository);

        BusinessOrderAcceptanceResponse accepted = service.accept(
                BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "correlation-1");
        BusinessOrderAcceptanceResponse replay = service.accept(
                BUSINESS_ID, BUSINESS_ORDER_ID, "\"0\"", "accept-key-001", "correlation-2");

        assertThat(accepted).isEqualTo(replay);
        assertThat(accepted.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT fulfillment_status FROM business_orders WHERE id = ?
                """, String.class, BUSINESS_ORDER_ID)).isEqualTo("ACCEPTED");
        assertThat(count("business_order_status_history")).isEqualTo(1);
        assertThat(count("order_outbox_events")).isEqualTo(1);
        assertThat(count("business_order_acceptance_commands")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM business_order_acceptance_commands
                """, String.class)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT reason_code FROM business_order_status_history
                """, String.class)).isEqualTo("BUSINESS_ACCEPTED");

        String payloadJson = jdbc.queryForObject("""
                SELECT payload_json FROM order_outbox_events
                WHERE event_type = 'business_order.accepted'
                """, String.class);
        Map<String, Object> payload = objectMapper.readValue(
                payloadJson, new TypeReference<>() {
                });
        assertThat(payload.keySet()).containsExactlyInAnyOrder(
                "eventId",
                "eventType",
                "eventVersion",
                "occurredAt",
                "businessOrderId",
                "orderId",
                "businessId",
                "fulfillmentStatus",
                "businessOrderVersion");
        assertThat(payloadJson).doesNotContain(
                "buyer", "address", "item", "payment", "provider",
                "idempotency", "actor");
        assertThat(jdbc.queryForObject("""
                SELECT causation_id FROM order_outbox_events
                """, String.class)).isEqualTo(jdbc.queryForObject("""
                SELECT id FROM business_order_acceptance_commands
                """, String.class));
    }

    @Test
    void hashConflictTenantHidingAndPreconditionFailuresAreBounded() {
        seed("SUCCEEDED", "PENDING_ACCEPTANCE", 0);
        BusinessOrderAcceptanceService service = service(repository);
        service.accept(
                BUSINESS_ID, BUSINESS_ORDER_ID, "0", "accept-key-001", "c");

        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "1", "accept-key-001", "c"),
                "BUSINESS_ORDER_IDEMPOTENCY_CONFLICT");
        assertCode(
                () -> service.accept(
                        OTHER_BUSINESS_ID, BUSINESS_ORDER_ID, "1",
                        "other-key-001", "c"),
                "BUSINESS_ORDER_NOT_FOUND");

        clean();
        seed("SUCCEEDED", "PENDING_ACCEPTANCE", 1);
        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "version-key-001", "c"),
                "BUSINESS_ORDER_VERSION_CONFLICT");

        clean();
        seed("SUCCEEDED", "ACCEPTED", 0);
        assertCode(
                () -> service.accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "state-key-001", "c"),
                "BUSINESS_ORDER_STATE_CONFLICT");
    }

    @Test
    void unpaidParentIsRejectedWithoutDurableCommandOrTransition() {
        jdbc.update("ALTER TABLE orders DROP CHECK chk_order_payment_status");
        seed("FAILED", "PENDING_ACCEPTANCE", 0);

        assertCode(
                () -> service(repository).accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0", "unpaid-key-001", "c"),
                "BUSINESS_ORDER_NOT_PAID");

        assertThat(count("business_order_acceptance_commands")).isZero();
        assertThat(count("business_order_status_history")).isZero();
        assertThat(count("order_outbox_events")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT fulfillment_status FROM business_orders WHERE id = ?
                """, String.class, BUSINESS_ORDER_ID)).isEqualTo("PENDING_ACCEPTANCE");
    }

    @Test
    void sameKeyConcurrencyExecutesOnceAndReplays() throws Exception {
        seed("SUCCEEDED", "PENDING_ACCEPTANCE", 0);
        BusinessOrderAcceptanceService service = service(repository);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.accept(
                    BUSINESS_ID, BUSINESS_ORDER_ID, "0", "same-key-001", "c1"));
            var second = executor.submit(() -> service.accept(
                    BUSINESS_ID, BUSINESS_ORDER_ID, "0", "same-key-001", "c2"));

            assertThat(first.get(20, TimeUnit.SECONDS).version()).isEqualTo(1);
            assertThat(second.get(20, TimeUnit.SECONDS).version()).isEqualTo(1);
        }
        assertThat(count("business_order_acceptance_commands")).isEqualTo(1);
        assertThat(count("business_order_status_history")).isEqualTo(1);
        assertThat(count("order_outbox_events")).isEqualTo(1);
    }

    @Test
    void distinctKeyConcurrencyAllowsOneSuccessAndOneBoundedConflict()
            throws Exception {
        seed("SUCCEEDED", "PENDING_ACCEPTANCE", 0);
        BusinessOrderAcceptanceService service = service(repository);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> outcome(
                    service, "distinct-key-001"));
            var second = executor.submit(() -> outcome(
                    service, "distinct-key-002"));

            assertThat(Set.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)))
                    .contains("ACCEPTED")
                    .anyMatch(value -> value.equals("BUSINESS_ORDER_VERSION_CONFLICT")
                            || value.equals("BUSINESS_ORDER_STATE_CONFLICT"));
        }
        assertThat(count("business_order_acceptance_commands")).isEqualTo(1);
        assertThat(count("business_order_status_history")).isEqualTo(1);
        assertThat(count("order_outbox_events")).isEqualTo(1);
    }

    @Test
    void outboxFailureRollsBackUpdateHistoryAndCommand() {
        seed("SUCCEEDED", "PENDING_ACCEPTANCE", 0);
        BusinessOrderAcceptanceRepository failing = spy(repository);
        doThrow(new RuntimeException("simulated outbox failure"))
                .when(failing)
                .insertOutbox(
                        anyString(), anyString(), anyString(),
                        anyString(), anyString(), any());

        assertCode(
                () -> service(failing).accept(
                        BUSINESS_ID, BUSINESS_ORDER_ID, "0",
                        "rollback-key-001", "c"),
                "BUSINESS_ORDER_ACCEPTANCE_UNAVAILABLE");

        assertThat(jdbc.queryForObject("""
                SELECT fulfillment_status FROM business_orders WHERE id = ?
                """, String.class, BUSINESS_ORDER_ID)).isEqualTo("PENDING_ACCEPTANCE");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM business_orders WHERE id = ?
                """, Long.class, BUSINESS_ORDER_ID)).isZero();
        assertThat(count("business_order_acceptance_commands")).isZero();
        assertThat(count("business_order_status_history")).isZero();
        assertThat(count("order_outbox_events")).isZero();
    }

    private BusinessOrderAcceptanceService service(
            BusinessOrderAcceptanceRepository acceptanceRepository) {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        when(actors.currentActor()).thenReturn(
                new CurrentActor("subject", "actor-token", null, null, true));
        BusinessOrderAuthorizationClient authorization =
                mock(BusinessOrderAuthorizationClient.class);
        when(authorization.authorizeFulfillment("actor-token", BUSINESS_ID))
                .thenReturn(new BusinessOrderAuthorizationClient.Access(
                        BUSINESS_ID, ACTOR_ID, "OWNER", true));
        when(authorization.authorizeFulfillment("actor-token", OTHER_BUSINESS_ID))
                .thenReturn(new BusinessOrderAuthorizationClient.Access(
                        OTHER_BUSINESS_ID, ACTOR_ID, "OWNER", true));
        return new BusinessOrderAcceptanceService(
                new BusinessOrderAcceptanceProperties(true),
                actors,
                authorization,
                acceptanceRepository,
                new BusinessOrderMetrics(new SimpleMeterRegistry()),
                new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)),
                objectMapper,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seed(String paymentStatus, String fulfillmentStatus, long version) {
        String checkoutId = id(10);
        String orderId = id(11);
        jdbc.update("""
                        INSERT INTO checkout_sessions (
                            id, buyer_id, status, version, cart_version, cart_snapshot_hash,
                            currency, subtotal, shipping, tax, discount, total, expires_at,
                            release_status, created_at, updated_at
                        ) VALUES (?, ?, 'COMPLETED', 2, 1, ?, 'USD', 20, 0, 0, 0, 20,
                                  ?, 'COMPLETE', ?, ?)
                        """,
                checkoutId,
                id(12),
                "a".repeat(64),
                Timestamp.from(NOW.plusSeconds(900)),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update("""
                        INSERT INTO orders (
                            id, checkout_id, payment_intent_id, buyer_id, order_number,
                            currency, subtotal, shipping, tax, discount, total,
                            payment_status, status, confirmed_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'USD', 20, 0, 0, 0, 20,
                                  ?, 'CONFIRMED', ?, ?, ?)
                        """,
                orderId,
                checkoutId,
                id(13),
                id(12),
                id(14),
                paymentStatus,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update("""
                        INSERT INTO business_orders (
                            id, order_id, business_id, store_id, seller_order_number,
                            fulfillment_status, cancellation_status, subtotal, shipping,
                            tax, discount, total, platform_fee_projection, version,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'NONE', 20, 0, 0, 0, 20,
                                  NULL, ?, ?, ?)
                        """,
                BUSINESS_ORDER_ID,
                orderId,
                BUSINESS_ID,
                id(15),
                id(16),
                fulfillmentStatus,
                version,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    private String outcome(
            BusinessOrderAcceptanceService service,
            String key) {
        try {
            return service.accept(
                    BUSINESS_ID, BUSINESS_ORDER_ID, "0", key, "c").fulfillmentStatus();
        } catch (BusinessOrderException exception) {
            return exception.code();
        }
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessOrderException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
