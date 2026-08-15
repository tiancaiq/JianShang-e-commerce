package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.OrderConfirmationProperties;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutPaymentBinding;
import com.msb.ecom.order_service.model.CheckoutReleaseStatus;
import com.msb.ecom.order_service.model.CheckoutStatus;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import com.msb.ecom.order_service.model.OrderConfirmationResult;
import com.msb.ecom.order_service.model.PaymentEventEnvelope;
import com.msb.ecom.order_service.repository.CheckoutPaymentBindingRepository;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.OrderConfirmationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class PaymentSucceededOrderConfirmationMySqlIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private static final String CHECKOUT_ID = id(1);
    private static final String BUYER_ID = id(2);
    private static final String PAYMENT_ID = id(800);
    private static final String RESERVATION_ID = id(700);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    private static JdbcTemplate jdbc;
    private static CheckoutRepository checkouts;
    private static CheckoutPaymentBindingRepository bindings;
    private static OrderConfirmationRepository orders;
    private static DataSourceTransactionManager transactionManager;
    private static ObjectMapper objectMapper;

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
        objectMapper = new ObjectMapper().findAndRegisterModules();
        checkouts = new CheckoutRepository(jdbc);
        bindings = new CheckoutPaymentBindingRepository(jdbc, objectMapper);
        orders = new OrderConfirmationRepository(jdbc);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM order_outbox_events");
        jdbc.update("DELETE FROM processed_payment_events");
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
    void confirmsMultiBusinessOrderAtomicallyAndReplaysWithoutAnotherCommit() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        var handler = handler(inventory, orders, NOW);

        OrderConfirmationResult confirmed = handler.handle(event(900));
        OrderConfirmationResult replay = handler.handle(event(900));

        assertThat(confirmed.outcome()).isEqualTo(OrderConfirmationResult.Outcome.CONFIRMED);
        assertThat(replay.outcome()).isEqualTo(OrderConfirmationResult.Outcome.REPLAYED);
        assertThat(replay.orderId()).isEqualTo(confirmed.orderId());
        assertThat(inventory.calls).hasValue(1);
        assertThat(inventory.sideEffects).hasValue(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("business_orders")).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT store_name FROM business_orders ORDER BY business_id",
                String.class)).containsExactly("Demo Store 200", "Demo Store 201");
        assertThat(count("order_items")).isEqualTo(2);
        assertThat(count("order_addresses")).isEqualTo(1);
        assertThat(count("order_status_history")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM order_outbox_events
                WHERE event_type = 'order.confirmed'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM checkout_sessions WHERE id = ?
                """, String.class, CHECKOUT_ID)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM processed_payment_events
                WHERE consumer_name = ? AND event_id = ?
                """, String.class, consumerName(), id(900))).isEqualTo("COMPLETED");
        String v1Payload = jdbc.queryForObject("""
                SELECT payload_json FROM order_outbox_events
                WHERE event_type = 'order.confirmed' AND event_version = 1
                """, String.class);
        assertThat(v1Payload)
                .contains(confirmed.orderId(), CHECKOUT_ID, PAYMENT_ID)
                .doesNotContain("1 Main St", "+15550123456", BUYER_ID);
        String v2Payload = jdbc.queryForObject("""
                SELECT payload_json FROM order_outbox_events
                WHERE event_type = 'order.confirmed' AND event_version = 2
                """, String.class);
        assertThat(v2Payload)
                .contains(confirmed.orderId(), CHECKOUT_ID, PAYMENT_ID, BUYER_ID)
                .doesNotContain("1 Main St", "+15550123456", "Buyer");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM order_outbox_events e
                JOIN orders o ON o.id = e.aggregate_id
                WHERE e.event_type = 'order.confirmed'
                  AND e.event_version = 2
                  AND e.aggregate_type = 'ORDER'
                  AND JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.orderId')) = e.aggregate_id
                  AND JSON_UNQUOTE(JSON_EXTRACT(e.payload_json, '$.recipientUserId')) = o.buyer_id
                  AND e.correlation_id = ?
                  AND e.causation_id = ?
                """, Integer.class, "correlation-order-900", id(900))).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateIsInProgressAndCreatesOneOrder() throws Exception {
        CheckoutAggregate checkout = seedPendingCheckout();
        BlockingInventory inventory = new BlockingInventory(checkout);
        var handler = handler(inventory, orders, NOW);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> handler.handle(event(901)));
            assertThat(inventory.entered.await(10, TimeUnit.SECONDS)).isTrue();

            OrderConfirmationResult duplicate = handler.handle(event(901));
            assertThat(duplicate.outcome()).isEqualTo(OrderConfirmationResult.Outcome.IN_PROGRESS);

            inventory.release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).outcome())
                    .isEqualTo(OrderConfirmationResult.Outcome.CONFIRMED);
        }
        assertThat(inventory.calls).hasValue(1);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void eventIdPayloadConflictFailsClosedWithoutAnotherInventoryCall() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        var handler = handler(inventory, orders, NOW);
        handler.handle(event(902));

        PaymentEventEnvelope original = event(902);
        PaymentEventEnvelope conflicting = new PaymentEventEnvelope(
                original.eventId(),
                original.eventType(),
                original.schemaVersion(),
                original.occurredAt(),
                "different-correlation",
                original.paymentIntentId(),
                original.checkoutId(),
                original.partitionKey(),
                original.payload());

        assertThatThrownBy(() -> handler.handle(conflicting))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("PAYMENT_EVENT_PAYLOAD_CONFLICT"));
        assertThat(inventory.calls).hasValue(1);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void rejectsUnknownAmountAndPaymentFailedEventsDurably() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        var handler = handler(inventory, orders, NOW);

        PaymentEventEnvelope unknown = withPayment(event(903), id(899));
        PaymentEventEnvelope amountMismatch = withAmount(event(904), "31.0000");
        PaymentEventEnvelope failed = failedEvent(905);

        assertThat(handler.handle(unknown).safeCode()).isEqualTo("PAYMENT_INTENT_UNKNOWN");
        assertThat(handler.handle(amountMismatch).safeCode())
                .isEqualTo("PAYMENT_CHECKOUT_MISMATCH");
        assertThat(handler.handle(failed).safeCode())
                .isEqualTo("PAYMENT_FAILED_EVENT_UNSUPPORTED");
        assertThat(inventory.calls).hasValue(0);
        assertThat(count("orders")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM processed_payment_events WHERE state = 'REJECTED'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void rejectsCrossBuyerAndCrossBusinessBindingsWithoutTenantLeakage() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        var handler = handler(inventory, orders, NOW);

        jdbc.update("""
                UPDATE checkout_payment_intents SET buyer_id = ?
                WHERE payment_intent_id = ?
                """, id(999), PAYMENT_ID);
        OrderConfirmationResult buyerMismatch = handler.handle(event(906));
        assertThat(buyerMismatch.safeCode()).isEqualTo("PAYMENT_CHECKOUT_MISMATCH");

        jdbc.update("""
                UPDATE checkout_payment_intents
                SET buyer_id = ?, business_ids_json = JSON_ARRAY(?)
                WHERE payment_intent_id = ?
                """, BUYER_ID, id(999), PAYMENT_ID);
        OrderConfirmationResult businessMismatch = handler.handle(event(907));
        assertThat(businessMismatch.safeCode()).isEqualTo("PAYMENT_CHECKOUT_MISMATCH");

        assertThat(inventory.calls).hasValue(0);
        assertThat(count("orders")).isZero();
    }

    @Test
    void rejectsLateAndIllegalCheckoutStatesWithoutCommittingInventory() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        var handler = handler(inventory, orders, NOW);
        checkouts.expire(CHECKOUT_ID, NOW);

        OrderConfirmationResult late = handler.handle(event(908));
        assertThat(late.safeCode()).isEqualTo("ORDER_CONFIRMATION_REQUIRES_RECOVERY");

        clean();
        CheckoutAggregate reserving = seedReservingCheckout();
        FakeInventory reservingInventory = new FakeInventory(reserving);
        OrderConfirmationResult illegal =
                handler(reservingInventory, orders, NOW).handle(event(909));
        assertThat(illegal.safeCode()).isEqualTo("CHECKOUT_STATE_ILLEGAL");
        assertThat(inventory.calls).hasValue(0);
        assertThat(reservingInventory.calls).hasValue(0);
        assertThat(count("orders")).isZero();
    }

    @Test
    void listingRestrictionRetriesBeforeInventoryCommitOrOrderCreation() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        ProductCommerceClient products = mock(ProductCommerceClient.class);
        doThrow(new com.msb.ecom.order_service.model.CheckoutException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "LISTING_PURCHASABILITY_RESTRICTED",
                "This item is unavailable for purchase."))
                .when(products).requirePurchasable(checkout.items().stream()
                        .map(CheckoutAggregate.Item::listingId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        var handler = new PaymentSucceededOrderConfirmationHandler(
                new OrderConfirmationProperties(true, consumerName(), Duration.ofSeconds(30)),
                new PaymentEventValidator(), checkouts, bindings, orders, inventory, null, products,
                new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)), objectMapper,
                transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));

        OrderConfirmationResult result = handler.handle(event(913));

        assertThat(result.outcome()).isEqualTo(OrderConfirmationResult.Outcome.RETRY_REQUIRED);
        assertThat(result.safeCode()).isEqualTo("LISTING_PURCHASABILITY_RESTRICTED");
        verify(products).requirePurchasable(checkout.items().stream()
                .map(CheckoutAggregate.Item::listingId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertThat(inventory.calls).hasValue(0);
        assertThat(count("orders")).isZero();
    }

    @Test
    void retryableInventoryFailureCompletesAfterHandlerRestart() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        inventory.remainingFailures.set(1);

        OrderConfirmationResult first = handler(inventory, orders, NOW).handle(event(910));
        OrderConfirmationResult retry =
                handler(inventory, orders, NOW.plusSeconds(1)).handle(event(910));

        assertThat(first.outcome()).isEqualTo(OrderConfirmationResult.Outcome.RETRY_REQUIRED);
        assertThat(retry.outcome()).isEqualTo(OrderConfirmationResult.Outcome.CONFIRMED);
        assertThat(inventory.calls).hasValue(2);
        assertThat(inventory.sideEffects).hasValue(1);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void abandonedProcessingLeaseRecoversAfterCrashAndRestart() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory crashing = new FakeInventory(checkout);
        crashing.crash.set(true);

        assertThatThrownBy(() -> handler(crashing, orders, NOW).handle(event(911)))
                .isInstanceOf(AssertionError.class);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM processed_payment_events WHERE event_id = ?
                """, String.class, id(911))).isEqualTo("PROCESSING");

        FakeInventory recovered = new FakeInventory(checkout);
        OrderConfirmationResult result =
                handler(recovered, orders, NOW.plusSeconds(31)).handle(event(911));
        assertThat(result.outcome()).isEqualTo(OrderConfirmationResult.Outcome.CONFIRMED);
        assertThat(recovered.sideEffects).hasValue(1);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void localOrderTransactionRollsBackAndRetryCompletesFromCommittedInventory() {
        CheckoutAggregate checkout = seedPendingCheckout();
        FakeInventory inventory = new FakeInventory(checkout);
        OrderConfirmationRepository failingOrders = spy(orders);
        doThrow(new RuntimeException("simulated persistence failure"))
                .when(failingOrders)
                .insertNotificationOutboxV2(any(), any(), any(), any(), any(), any());

        OrderConfirmationResult first =
                handler(inventory, failingOrders, NOW).handle(event(912));
        assertThat(first.outcome()).isEqualTo(OrderConfirmationResult.Outcome.RETRY_REQUIRED);
        assertThat(count("orders")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM checkout_sessions WHERE id = ?
                """, String.class, CHECKOUT_ID)).isEqualTo("PAYMENT_PROCESSING");

        OrderConfirmationResult retry =
                handler(inventory, orders, NOW.plusSeconds(1)).handle(event(912));
        assertThat(retry.outcome()).isEqualTo(OrderConfirmationResult.Outcome.CONFIRMED);
        assertThat(inventory.sideEffects).hasValue(1);
        assertThat(count("orders")).isEqualTo(1);
    }

    private PaymentSucceededOrderConfirmationHandler handler(
            InventoryReservationClient inventory,
            OrderConfirmationRepository repository,
            Instant now) {
        return new PaymentSucceededOrderConfirmationHandler(
                new OrderConfirmationProperties(true, consumerName(), Duration.ofSeconds(30)),
                new PaymentEventValidator(),
                checkouts,
                bindings,
                repository,
                inventory,
                new CheckoutUlidGenerator(Clock.fixed(now, ZoneOffset.UTC)),
                objectMapper,
                transactionManager,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private CheckoutAggregate seedPendingCheckout() {
        CheckoutAggregate created = checkout(CheckoutStatus.RESERVING);
        checkouts.insert(created);
        checkouts.markPending(CHECKOUT_ID, RESERVATION_ID, "ACTIVE", 1L, NOW);
        CheckoutAggregate pending = checkouts.find(CHECKOUT_ID).orElseThrow();
        insertBinding(pending);
        return pending;
    }

    private CheckoutAggregate seedReservingCheckout() {
        CheckoutAggregate reserving = checkout(CheckoutStatus.RESERVING);
        checkouts.insert(reserving);
        insertBinding(reserving);
        return reserving;
    }

    private void insertBinding(CheckoutAggregate checkout) {
        bindings.insertOrVerify(new CheckoutPaymentBinding(
                PAYMENT_ID,
                checkout.id(),
                checkout.version(),
                checkout.cartSnapshotHash(),
                checkout.buyerId(),
                checkout.items().stream()
                        .map(CheckoutAggregate.Item::businessId)
                        .distinct()
                        .sorted()
                        .toList(),
                checkout.total(),
                checkout.currency(),
                checkout.expiresAt(),
                NOW));
    }

    private CheckoutAggregate checkout(CheckoutStatus status) {
        return new CheckoutAggregate(
                CHECKOUT_ID,
                BUYER_ID,
                status,
                0,
                3,
                "a".repeat(64),
                "USD",
                new BigDecimal("30.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("30.0000"),
                NOW.plusSeconds(900),
                null,
                null,
                null,
                CheckoutReleaseStatus.NOT_REQUIRED,
                null,
                NOW.minusSeconds(60),
                NOW.minusSeconds(60),
                new CheckoutAggregate.Address(
                        id(50), 1, "Home", "Buyer", "+15550123456",
                        "1 Main St", null, "Irvine", "CA", "92618", "US"),
                List.of(
                        item(1, 100, 200, 300, "10.0000"),
                        item(2, 101, 201, 301, "20.0000")),
                List.of(
                        shipping(100, 200, 600),
                        shipping(101, 201, 601)),
                new CheckoutAggregate.TaxQuote(
                        id(650), BigDecimal.ZERO, "USD",
                        "ZERO_LOCAL_DEMO_V1", NOW.minusSeconds(60)),
                List.of(
                        policy(100, 200, 400),
                        policy(101, 201, 401)));
    }

    private CheckoutAggregate.Item item(
            int line,
            int business,
            int store,
            int listing,
            String amount) {
        return new CheckoutAggregate.Item(
                id(500 + line),
                line,
                id(listing),
                id(business),
                id(store),
                "Demo Store " + store,
                1,
                "Store item " + line,
                "SKU-" + line,
                "NEW",
                null,
                1,
                new BigDecimal(amount),
                "USD",
                new BigDecimal(amount),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(amount),
                id(400 + line - 1),
                "LOCAL_DEMO_V1");
    }

    private CheckoutAggregate.Policy policy(int business, int store, int idSuffix) {
        return new CheckoutAggregate.Policy(
                id(idSuffix),
                id(business),
                id(store),
                "01KXQCHKPOLICYLOCALDEMOV10",
                "PLATFORM_DEFAULT",
                "LOCAL_DEMO_V1",
                "Shipping text",
                "Cancellation text",
                "Return text");
    }

    private CheckoutAggregate.ShippingQuote shipping(int business, int store, int idSuffix) {
        return new CheckoutAggregate.ShippingQuote(
                id(idSuffix),
                id(business),
                id(store),
                "FREE_LOCAL_DEMO",
                BigDecimal.ZERO,
                "USD",
                "FREE_LOCAL_DEMO_V1",
                NOW.minusSeconds(60));
    }

    private PaymentEventEnvelope event(int suffix) {
        return new PaymentEventEnvelope(
                id(suffix),
                "payment.succeeded",
                1,
                NOW,
                "correlation-order-" + suffix,
                PAYMENT_ID,
                CHECKOUT_ID,
                PAYMENT_ID,
                new PaymentEventEnvelope.Payload(
                        "SUCCEEDED",
                        new BigDecimal("30.0000"),
                        "USD",
                        "fake_evt_order_" + suffix));
    }

    private PaymentEventEnvelope failedEvent(int suffix) {
        PaymentEventEnvelope event = event(suffix);
        return new PaymentEventEnvelope(
                event.eventId(),
                "payment.failed",
                1,
                event.occurredAt(),
                event.correlationId(),
                event.paymentIntentId(),
                event.checkoutId(),
                event.partitionKey(),
                new PaymentEventEnvelope.Payload(
                        "FAILED",
                        event.payload().amount(),
                        event.payload().currency(),
                        event.payload().providerEventId()));
    }

    private PaymentEventEnvelope withPayment(PaymentEventEnvelope event, String paymentId) {
        return new PaymentEventEnvelope(
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.occurredAt(),
                event.correlationId(),
                paymentId,
                event.checkoutId(),
                paymentId,
                event.payload());
    }

    private PaymentEventEnvelope withAmount(PaymentEventEnvelope event, String amount) {
        return new PaymentEventEnvelope(
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.occurredAt(),
                event.correlationId(),
                event.paymentIntentId(),
                event.checkoutId(),
                event.partitionKey(),
                new PaymentEventEnvelope.Payload(
                        event.payload().status(),
                        new BigDecimal(amount),
                        event.payload().currency(),
                        event.payload().providerEventId()));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String consumerName() {
        return "order-service-order-confirmation-v1";
    }

    private static String id(int suffix) {
        return "01K" + String.format("%023d", suffix);
    }

    private static class FakeInventory implements InventoryReservationClient {
        private final CheckoutAggregate checkout;
        final AtomicInteger remainingFailures = new AtomicInteger();
        final AtomicBoolean crash = new AtomicBoolean();
        final AtomicBoolean committed = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger sideEffects = new AtomicInteger();

        private FakeInventory(CheckoutAggregate checkout) {
            this.checkout = checkout;
        }

        @Override
        public Reservation reserve(String checkoutId, Instant expiresAt, List<Line> lines) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Reservation get(String reservationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Reservation release(String checkoutId, String reservationId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Reservation commit(String checkoutId, String reservationId, String correlationId) {
            calls.incrementAndGet();
            if (crash.get()) {
                throw new AssertionError("simulated process crash");
            }
            if (remainingFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new RuntimeException("simulated inventory outage");
            }
            if (committed.compareAndSet(false, true)) {
                sideEffects.incrementAndGet();
            }
            return new Reservation(
                    RESERVATION_ID,
                    CHECKOUT_ID,
                    "CHECKOUT",
                    "COMMITTED",
                    false,
                    checkout.expiresAt(),
                    2,
                    checkout.items().stream()
                            .map(item -> new Item(item.listingId(), item.quantity()))
                            .toList());
        }
    }

    private static final class BlockingInventory extends FakeInventory {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingInventory(CheckoutAggregate checkout) {
            super(checkout);
        }

        @Override
        public Reservation commit(String checkoutId, String reservationId, String correlationId) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocking inventory timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return super.commit(checkoutId, reservationId, correlationId);
        }
    }
}
