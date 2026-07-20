package com.msb.ecom.payment_service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.config.PaymentOutboxProperties;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import com.msb.ecom.payment_service.repository.PaymentOutboxRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentOutboxDispatcherMySqlIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.3.0");

    private static JdbcTemplate jdbc;
    private static PaymentIntentRepository intents;
    private static PaymentOutboxRepository outbox;
    private static PlatformTransactionManager transactionManager;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        intents = new PaymentIntentRepository(jdbc);
        outbox = new PaymentOutboxRepository(jdbc);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM payment_outbox_events");
        jdbc.update("DELETE FROM payment_provider_events");
        jdbc.update("DELETE FROM payment_idempotency_records");
        jdbc.update("DELETE FROM payment_status_history");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM payment_intent_business_scopes");
        jdbc.update("DELETE FROM payment_intents");
    }

    @Test
    void publishesStableEnvelopeAndMarksTheEventOnlyOnce() {
        insertEvent(1);
        InMemoryTransport transport = new InMemoryTransport(0);
        PaymentOutboxDispatcher dispatcher = dispatcher(transport, NOW, 10);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(dispatcher.dispatchBatch()).isZero();

        assertThat(transport.messages).singleElement().satisfies(message -> {
            assertThat(message.eventId()).isEqualTo(id(3));
            assertThat(message.eventType()).isEqualTo("payment.succeeded");
            assertThat(message.schemaVersion()).isEqualTo(1);
            assertThat(message.paymentIntentId()).isEqualTo(id(1));
            assertThat(message.checkoutId()).isEqualTo(id(2));
            assertThat(message.partitionKey()).isEqualTo(id(1));
            assertThat(message.payload().amount()).isEqualByComparingTo("42.2500");
        });
        var row = jdbc.queryForMap("""
                SELECT published_at, retry_count, attempt_count, claim_token,
                       last_error_code, terminal_failure_at
                FROM payment_outbox_events WHERE id = ?
                """, id(3));
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("retry_count")).isEqualTo(0L);
        assertThat(row.get("attempt_count")).isEqualTo(1L);
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("last_error_code")).isNull();
        assertThat(row.get("terminal_failure_at")).isNull();
    }

    @Test
    void concurrentWorkersClaimAndPublishOneRowOnce() throws Exception {
        insertEvent(10);
        InMemoryTransport transport = new InMemoryTransport(0);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> dispatchAfter(start, dispatcher(transport, NOW, 10)));
            var second = executor.submit(() -> dispatchAfter(start, dispatcher(transport, NOW, 10)));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(transport.messages).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM payment_outbox_events WHERE id = ?",
                Integer.class,
                id(12))).isEqualTo(1);
    }

    @Test
    void failureBackoffAndRestartRetryPublishSafely() {
        insertEvent(20);
        InMemoryTransport failing = new InMemoryTransport(1);

        assertThat(dispatcher(failing, NOW, 10).dispatchBatch()).isZero();
        var failed = jdbc.queryForMap("""
                SELECT retry_count, attempt_count, next_attempt_at, claim_token,
                       last_error_code, last_error_message
                FROM payment_outbox_events WHERE id = ?
                """, id(22));
        assertThat(failed.get("retry_count")).isEqualTo(1L);
        assertThat(failed.get("attempt_count")).isEqualTo(1L);
        Instant nextAttemptAt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM payment_outbox_events WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                id(22));
        assertThat(nextAttemptAt).isEqualTo(NOW.plusSeconds(5));
        assertThat(failed.get("claim_token")).isNull();
        assertThat(failed.get("last_error_code")).isEqualTo("FAKE_TRANSPORT_FAILURE");
        assertThat(failed.get("last_error_message")).isEqualTo(
                "Payment event delivery will be retried.");

        InMemoryTransport recovered = new InMemoryTransport(0);
        assertThat(dispatcher(recovered, NOW.plusSeconds(4), 10).dispatchBatch()).isZero();
        assertThat(dispatcher(recovered, NOW.plusSeconds(5), 10).dispatchBatch()).isEqualTo(1);
        assertThat(recovered.messages).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM payment_outbox_events WHERE id = ?",
                Integer.class,
                id(22))).isEqualTo(2);
    }

    @Test
    void expiredLeaseIsRecoveredAfterWorkerRestart() {
        insertEvent(30);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.execute(status -> outbox.claim(
                "abandoned-claim",
                NOW,
                NOW.plusSeconds(1),
                25));

        InMemoryTransport transport = new InMemoryTransport(0);
        assertThat(dispatcher(transport, NOW.plusSeconds(2), 10).dispatchBatch()).isEqualTo(1);
        assertThat(transport.messages).hasSize(1);
    }

    @Test
    void retryLimitStoresOnlyBoundedTerminalFailureAndStopsClaiming() {
        insertEvent(40);
        InMemoryTransport transport = new InMemoryTransport(10);

        assertThat(dispatcher(transport, NOW, 2).dispatchBatch()).isZero();
        assertThat(dispatcher(transport, NOW.plusSeconds(5), 2).dispatchBatch()).isZero();
        assertThat(dispatcher(transport, NOW.plusSeconds(60), 2).dispatchBatch()).isZero();

        var row = jdbc.queryForMap("""
                SELECT retry_count, attempt_count, next_attempt_at, terminal_failure_at,
                       last_error_code, last_error_message, claim_token
                FROM payment_outbox_events WHERE id = ?
                """, id(42));
        assertThat(row.get("retry_count")).isEqualTo(2L);
        assertThat(row.get("attempt_count")).isEqualTo(2L);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("terminal_failure_at")).isNotNull();
        assertThat(row.get("last_error_code")).isEqualTo("FAKE_TRANSPORT_FAILURE");
        assertThat(row.get("last_error_message")).isEqualTo(
                "Payment event delivery exhausted its retry limit.");
        assertThat(row.get("last_error_message").toString()).doesNotContain("secret");
        assertThat(row.get("claim_token")).isNull();
    }

    private int dispatchAfter(
            CountDownLatch start,
            PaymentOutboxDispatcher dispatcher) throws InterruptedException {
        start.await();
        return dispatcher.dispatchBatch();
    }

    private PaymentOutboxDispatcher dispatcher(
            PaymentEventTransport transport,
            Instant now,
            int maxAttempts) {
        return new PaymentOutboxDispatcher(
                outbox,
                new PaymentOutboxMessageFactory(new ObjectMapper().findAndRegisterModules()),
                transport,
                new PaymentOutboxProperties(
                        true,
                        false,
                        25,
                        maxAttempts,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(5)),
                transactionManager,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void insertEvent(int suffix) {
        PaymentIntent intent = new PaymentIntent(
                id(suffix),
                id(suffix + 1),
                1,
                "a".repeat(64),
                id(90),
                "ORDER_SERVICE",
                List.of(id(91)),
                new BigDecimal("42.2500"),
                "USD",
                "CARD",
                "AUTOMATIC",
                "PLATFORM",
                "SEPARATE_CHARGE_TRANSFER",
                "FAKE_LOCAL_DEMO_V1",
                "fake_pi_" + id(suffix).toLowerCase(),
                null,
                PaymentIntentStatus.SUCCEEDED,
                1,
                NOW.plusSeconds(900),
                null,
                null,
                NOW,
                NOW);
        intents.insert(intent, id(92), "correlation-payment");
        intents.insertOutbox(
                id(suffix + 2),
                intent.id(),
                "payment.succeeded",
                """
                        {
                          "paymentIntentId":"%s",
                          "checkoutId":"%s",
                          "status":"SUCCEEDED",
                          "amount":"42.2500",
                          "currency":"USD",
                          "providerEventId":"fake_evt_%04d"
                        }
                """.formatted(intent.id(), intent.checkoutId(), suffix),
                "correlation-payment",
                "fake_evt_%04d".formatted(suffix),
                NOW,
                NOW);
    }

    private String id(int suffix) {
        return "01K" + String.format("%023d", suffix);
    }

    private static final class InMemoryTransport implements PaymentEventTransport {
        private final AtomicInteger remainingFailures;
        private final List<PaymentEventMessage> messages = new CopyOnWriteArrayList<>();

        private InMemoryTransport(int failures) {
            this.remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void publish(PaymentEventMessage message) {
            if (remainingFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new PaymentEventTransportException("FAKE_TRANSPORT_FAILURE");
            }
            messages.add(message);
        }
    }
}
