package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.OrderCancellationProperties;
import com.msb.ecom.order_service.dto.OrderCancellationResponse;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.repository.OrderCancellationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class OrderCancellationMySqlIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-20T02:00:00Z");
    private static final String BUYER_ID = id(90);
    private static final String OTHER_BUYER_ID = id(91);
    private static final String FUTURE_POLICY_ID = "01999999999999999999999991";
    private static final String FUTURE_POLICY_VERSION =
            "LOCAL_DEMO_CANCELLATION_TEST_V1";
    private static final String LOCAL_DEMO_POLICY_ID = "01KXQCHKPOLICYLOCALDEMOV10";
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    @TempDir
    static Path migrationDirectory;

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static boolean v1ToV4ToV6Verified;

    @AfterAll
    static void stopDisposableMySql() {
        MYSQL.stop();
    }

    @BeforeAll
    static void migrateReleaseLineage() throws IOException {
        MYSQL.start();
        DriverManagerDataSource dataSource = dataSource();
        copyMigration("V1__init.sql");
        copyMigration("V2__create_checkout_foundation.sql");
        copyMigration("V3__create_payment_succeeded_order_confirmation.sql");
        copyMigration("V4__index_unfiltered_business_order_queue.sql");
        copyMigration("V6__create_order_cancellation_request_foundation.sql");

        Flyway baseline = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrationDirectory.toAbsolutePath())
                .load();
        baseline.migrate();
        JdbcTemplate baselineJdbc = new JdbcTemplate(dataSource);
        assertPolicyModes(baselineJdbc);
        assertThat(baseline.info().current().getVersion().getVersion()).isEqualTo("6");
        assertThat(baseline.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "6");
        v1ToV4ToV6Verified = true;

        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM order_cancellation_commands");
        jdbc.update("DELETE FROM business_order_cancellation_history");
        jdbc.update("DELETE FROM order_cancellation_request_groups");
        jdbc.update("DELETE FROM order_cancellation_requests");
        jdbc.update("DELETE FROM order_outbox_events");
        jdbc.update("DELETE FROM order_status_history");
        jdbc.update("DELETE FROM order_addresses");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM business_orders");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM checkout_payment_intents");
        jdbc.update("DELETE FROM order_idempotency_records");
        jdbc.update("DELETE FROM checkout_status_history");
        jdbc.update("DELETE FROM checkout_items");
        jdbc.update("DELETE FROM checkout_shipping_quotes");
        jdbc.update("DELETE FROM checkout_tax_quotes");
        jdbc.update("DELETE FROM checkout_policy_snapshots");
        jdbc.update("DELETE FROM checkout_addresses");
        jdbc.update("DELETE FROM checkout_sessions");
    }

    @Test
    void releaseLineagePreservesDefaultsAndStructuredFuturePolicy() {
        assertThat(v1ToV4ToV6Verified).isTrue();
        assertPolicyModes(jdbc);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'orders'
                  AND column_name = 'version'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'order_cancellation_requests'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredKeyCanEstablishFreshCommandForAnotherOrder() {
        String firstOrder = seedOrder(9, BUYER_ID, true, NOW.plusSeconds(600), 1);
        String secondOrder = seedOrder(10, BUYER_ID, true, NOW.plusSeconds(600), 1);
        OrderCancellationService service = service(
                new OrderCancellationRepository(jdbc), BUYER_ID);

        service.request(
                firstOrder, "0", "cancel-key-expired", false, "correlation");
        String expiredCommandId = value("""
                SELECT id FROM order_cancellation_commands
                WHERE buyer_id = ? AND idempotency_key = 'cancel-key-expired'
                """, BUYER_ID);
        jdbc.update("""
                UPDATE order_cancellation_commands
                SET expires_at = ?
                WHERE id = ?
                """, Timestamp.from(NOW), expiredCommandId);

        OrderCancellationResponse fresh = service.request(
                secondOrder, "0", "cancel-key-expired", false, "correlation");
        String freshCommandId = value("""
                SELECT id FROM order_cancellation_commands
                WHERE buyer_id = ? AND idempotency_key = 'cancel-key-expired'
                """, BUYER_ID);

        assertThat(fresh.orderId()).isEqualTo(secondOrder);
        assertThat(freshCommandId).isNotEqualTo(expiredCommandId);
        assertThat(value("""
                SELECT order_id FROM order_cancellation_commands
                WHERE id = ?
                """, freshCommandId)).isEqualTo(secondOrder);
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_requests"))
                .isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM order_outbox_events"))
                .isEqualTo(2);
    }

    @Test
    void requestsMultiGroupCancellationAtomicallyAndReplaysWithoutMoreEffects() {
        String orderId = seedOrder(1, BUYER_ID, true, NOW.plusSeconds(600), 2);
        OrderCancellationService service = service(
                new OrderCancellationRepository(jdbc), BUYER_ID);

        OrderCancellationResponse first = service.request(
                orderId, "\"0\"", "cancel-key-001", false, "cancel-correlation");
        OrderCancellationResponse replay = service.request(
                orderId, "0", "cancel-key-001", false, "cancel-correlation");
        OrderCancellationResponse semanticReplay = service.request(
                orderId, "1", "cancel-key-002", false, "cancel-correlation");

        assertThat(first.version()).isEqualTo(1);
        assertThat(replay).isEqualTo(first);
        assertThat(semanticReplay).isEqualTo(first);
        assertThat(value("SELECT status FROM orders WHERE id = ?", orderId))
                .isEqualTo("CANCELLATION_REQUESTED");
        assertThat(number("SELECT version FROM orders WHERE id = ?", orderId))
                .isEqualTo(1);
        assertThat(number("""
                SELECT COUNT(*) FROM business_orders
                WHERE order_id = ? AND cancellation_status = 'CANCELLATION_PENDING'
                """, orderId)).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_requests"))
                .isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_request_groups"))
                .isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM business_order_cancellation_history"))
                .isEqualTo(2);
        assertThat(number("""
                SELECT COUNT(*) FROM order_status_history
                WHERE order_id = ? AND to_status = 'CANCELLATION_REQUESTED'
                """, orderId)).isEqualTo(1);
        assertThat(number("""
                SELECT COUNT(*) FROM order_outbox_events
                WHERE aggregate_id = ? AND event_type = 'order.cancellation_requested'
                """, orderId)).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_commands"))
                .isEqualTo(2);

        String payload = value("""
                SELECT payload_json FROM order_outbox_events
                WHERE aggregate_id = ? AND event_type = 'order.cancellation_requested'
                """, orderId);
        assertThat(payload)
                .contains(orderId, first.cancellationRequestId())
                .doesNotContain(BUYER_ID, "payment_intent", "address", "idempotency");
    }

    @Test
    void rejectsCurrentPolicyClosedCutoffAndCrossBuyerWithoutSideEffects() {
        String currentPolicyOrder = seedOrder(2, BUYER_ID, false, NOW.plusSeconds(600), 1);
        String closedOrder = seedOrder(3, BUYER_ID, true, NOW, 1);
        String otherOrder = seedOrder(4, OTHER_BUYER_ID, true, NOW.plusSeconds(600), 1);
        OrderCancellationService service = service(
                new OrderCancellationRepository(jdbc), BUYER_ID);

        assertCode(() -> service.request(
                currentPolicyOrder, "0", "cancel-key-101", false, "correlation"),
                "ORDER_CANCELLATION_NOT_ALLOWED");
        assertCode(() -> service.request(
                closedOrder, "0", "cancel-key-102", false, "correlation"),
                "ORDER_CANCELLATION_WINDOW_CLOSED");
        assertCode(() -> service.request(
                otherOrder, "0", "cancel-key-103", false, "correlation"),
                "ORDER_NOT_FOUND");

        assertThat(number("SELECT COUNT(*) FROM order_cancellation_requests"))
                .isZero();
        assertThat(number("SELECT COUNT(*) FROM order_outbox_events"))
                .isZero();
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_commands"))
                .isZero();
    }

    @Test
    void detectsPayloadHashConflictAndDistinctKeyVersionRace() throws Exception {
        String hashOrder = seedOrder(5, BUYER_ID, true, NOW.plusSeconds(600), 1);
        OrderCancellationService service = service(
                new OrderCancellationRepository(jdbc), BUYER_ID);
        service.request(hashOrder, "0", "cancel-key-201", false, "correlation");

        assertCode(() -> service.request(
                hashOrder, "1", "cancel-key-201", false, "correlation"),
                "ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT");

        clean();
        String raceOrder = seedOrder(6, BUYER_ID, true, NOW.plusSeconds(600), 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> calls = List.of(
                () -> race(service, start, raceOrder, "cancel-key-301"),
                () -> race(service, start, raceOrder, "cancel-key-302"));
        List<String> outcomes = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = calls.stream().map(executor::submit).toList();
            start.countDown();
            for (var future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }
        }

        assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "ORDER_VERSION_CONFLICT");
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_requests"))
                .isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_outbox_events"))
                .isEqualTo(1);
    }

    @Test
    void sameKeyConcurrencyExecutesOnceAndReplaysOneResponse() throws Exception {
        String orderId = seedOrder(7, BUYER_ID, true, NOW.plusSeconds(600), 2);
        OrderCancellationService service = service(
                new OrderCancellationRepository(jdbc), BUYER_ID);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.request(
                        orderId, "0", "cancel-key-401", false, "correlation");
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return service.request(
                        orderId, "0", "cancel-key-401", false, "correlation");
            });
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS))
                    .isEqualTo(second.get(15, TimeUnit.SECONDS));
        }

        assertThat(number("SELECT COUNT(*) FROM order_cancellation_commands"))
                .isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_requests"))
                .isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_outbox_events"))
                .isEqualTo(1);
    }

    @Test
    void sameKeyAcrossDifferentOrdersProducesSuccessAndStableConflict() throws Exception {
        String firstOrder = seedOrder(11, BUYER_ID, true, NOW.plusSeconds(600), 1);
        String secondOrder = seedOrder(12, BUYER_ID, true, NOW.plusSeconds(600), 1);
        OrderCancellationService service = service(
                new OrderCancellationRepository(jdbc), BUYER_ID);
        CountDownLatch start = new CountDownLatch(1);
        List<String> outcomes = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(
                    () -> race(service, start, firstOrder, "cancel-key-cross-order"));
            var second = executor.submit(
                    () -> race(service, start, secondOrder, "cancel-key-cross-order"));
            start.countDown();
            outcomes.add(first.get(15, TimeUnit.SECONDS));
            outcomes.add(second.get(15, TimeUnit.SECONDS));
        }

        assertThat(outcomes).containsExactlyInAnyOrder(
                "SUCCESS", "ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT");
        assertThat(outcomes).doesNotContain("ORDER_CANCELLATION_UNAVAILABLE");
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_commands"))
                .isEqualTo(1);
        assertThat(number("""
                SELECT COUNT(*) FROM orders WHERE status = 'CANCELLATION_REQUESTED'
                """)).isEqualTo(1);
        assertThat(number("SELECT COUNT(*) FROM order_outbox_events"))
                .isEqualTo(1);
    }

    @Test
    void downstreamPersistenceFailureRollsBackEveryCancellationEffect() {
        String orderId = seedOrder(8, BUYER_ID, true, NOW.plusSeconds(600), 2);
        OrderCancellationRepository repository =
                spy(new OrderCancellationRepository(jdbc));
        doThrow(new DataIntegrityViolationException("forced rollback"))
                .when(repository)
                .insertOutbox(any(), any(), any(), any(), any(), any());
        OrderCancellationService service = service(repository, BUYER_ID);

        assertCode(() -> service.request(
                orderId, "0", "cancel-key-501", false, "correlation"),
                "ORDER_CANCELLATION_UNAVAILABLE");

        assertThat(value("SELECT status FROM orders WHERE id = ?", orderId))
                .isEqualTo("CONFIRMED");
        assertThat(number("SELECT version FROM orders WHERE id = ?", orderId))
                .isZero();
        assertThat(number("""
                SELECT COUNT(*) FROM business_orders
                WHERE order_id = ? AND cancellation_status <> 'NONE'
                """, orderId)).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_commands")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_cancellation_requests")).isZero();
        assertThat(number("SELECT COUNT(*) FROM business_order_cancellation_history")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_status_history")).isZero();
        assertThat(number("SELECT COUNT(*) FROM order_outbox_events")).isZero();
    }

    private static String race(
            OrderCancellationService service,
            CountDownLatch start,
            String orderId,
            String key) throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        try {
            service.request(orderId, "0", key, false, "correlation");
            return "SUCCESS";
        } catch (BuyerOrderException exception) {
            return exception.code();
        }
    }

    private OrderCancellationService service(
            OrderCancellationRepository repository,
            String buyerId) {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        BuyerIdentityClient buyers = mock(BuyerIdentityClient.class);
        when(actors.currentActor()).thenReturn(
                new CurrentActor("buyer-subject", "token", null, null, true));
        when(buyers.resolveBuyer("buyer-subject")).thenReturn(buyerId);
        return new OrderCancellationService(
                new OrderCancellationProperties(true, Duration.ofDays(7), 100),
                actors,
                buyers,
                repository,
                new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)),
                new OrderCancellationMetrics(new SimpleMeterRegistry()),
                new ObjectMapper().findAndRegisterModules(),
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String seedOrder(
            int value,
            String buyerId,
            boolean cancellationAllowed,
            Instant cutoff,
            int groupCount) {
        String checkoutId = id(1000 + value);
        String orderId = id(value);
        BigDecimal total = new BigDecimal("20.0000")
                .multiply(BigDecimal.valueOf(groupCount));
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
                total,
                total,
                Timestamp.from(NOW.plusSeconds(900)),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(60)));
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
                total,
                total,
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(60)));

        for (int group = 0; group < groupCount; group++) {
            String businessOrderId = id(3000 + value * 10 + group);
            String businessId = id(4000 + value * 10 + group);
            String storeId = id(5000 + value * 10 + group);
            String policySnapshotId = id(6000 + value * 10 + group);
            String policyId = cancellationAllowed ? FUTURE_POLICY_ID : LOCAL_DEMO_POLICY_ID;
            String policyVersion =
                    cancellationAllowed ? FUTURE_POLICY_VERSION : "LOCAL_DEMO_V1";
            String policyMode =
                    cancellationAllowed ? "BEFORE_FULFILLMENT" : "NOT_ALLOWED";
            jdbc.update("""
                            INSERT INTO checkout_policy_snapshots (
                                id, checkout_id, business_id, store_id, source_policy_id,
                                source, version_code, shipping_text, cancellation_text,
                                return_text, paid_order_cancellation_mode, snapshotted_at
                            ) VALUES (?, ?, ?, ?, ?, 'PLATFORM_DEFAULT', ?,
                                'shipping', 'cancellation', 'return', ?, ?)
                            """,
                    policySnapshotId,
                    checkoutId,
                    businessId,
                    storeId,
                    policyId,
                    policyVersion,
                    policyMode,
                    Timestamp.from(NOW.minusSeconds(60)));
            jdbc.update("""
                            INSERT INTO business_orders (
                                id, order_id, business_id, store_id, seller_order_number,
                                fulfillment_status, cancellation_status, cancellation_cutoff_at,
                                subtotal, shipping, tax, discount, total,
                                platform_fee_projection, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, 'PENDING_ACCEPTANCE', 'NONE', ?,
                                20, 0, 0, 0, 20, NULL, ?, ?)
                            """,
                    businessOrderId,
                    orderId,
                    businessId,
                    storeId,
                    businessOrderId,
                    Timestamp.from(cutoff),
                    Timestamp.from(NOW.minusSeconds(60)),
                    Timestamp.from(NOW.minusSeconds(60)));
            jdbc.update("""
                            INSERT INTO order_items (
                                id, order_id, business_order_id, line_number, listing_id,
                                business_id, store_id, catalog_version, title, sku,
                                item_condition, thumbnail_url, quantity, unit_price, currency,
                                line_subtotal, shipping_allocation, tax_allocation,
                                discount_allocation, line_total, policy_version, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'Snapshot item', NULL,
                                'NEW', NULL, 1, 20, 'USD', 20, 0, 0, 0, 20, ?, ?)
                            """,
                    id(7000 + value * 10 + group),
                    orderId,
                    businessOrderId,
                    group + 1,
                    id(8000 + value * 10 + group),
                    businessId,
                    storeId,
                    policyVersion,
                    Timestamp.from(NOW.minusSeconds(60)));
        }
        return orderId;
    }

    private static DriverManagerDataSource dataSource() {
        String jdbcUrl = MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        return new DriverManagerDataSource(
                jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void copyMigration(String name) throws IOException {
        try (InputStream source =
                     OrderCancellationMySqlIntegrationTests.class.getClassLoader()
                             .getResourceAsStream("db/migration/" + name)) {
            if (source == null) {
                throw new IOException("Missing migration " + name);
            }
            Files.copy(source, migrationDirectory.resolve(name));
        }
    }

    private static void assertPolicyModes(JdbcTemplate database) {
        assertThat(database.queryForObject("""
                SELECT paid_order_cancellation_mode
                FROM platform_policy_versions WHERE version_code = 'LOCAL_DEMO_V1'
                """, String.class)).isEqualTo("NOT_ALLOWED");
        assertThat(database.queryForObject("""
                SELECT paid_order_cancellation_mode
                FROM platform_policy_versions
                WHERE version_code = 'LOCAL_DEMO_CANCELLATION_TEST_V1'
                """, String.class)).isEqualTo("BEFORE_FULFILLMENT");
        assertThat(database.queryForObject("""
                SELECT effective_from
                FROM platform_policy_versions
                WHERE version_code = 'LOCAL_DEMO_CANCELLATION_TEST_V1'
                """, Timestamp.class).toInstant()).isEqualTo(Instant.parse("2037-01-01T00:00:00Z"));
    }

    private int number(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private String value(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    private static void assertCode(Runnable command, String code) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(
                        BuyerOrderException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
