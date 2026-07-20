package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.config.NotificationConsumerProperties;
import com.msb.ecom.notification_service.model.NotificationConsumeResult;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.msb.ecom.notification_service.service.OrderConfirmedEventParserTests.eventJson;
import static com.msb.ecom.notification_service.service.OrderConfirmedEventParserTests.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class OrderConfirmedNotificationMySqlIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("notifications")
            .withUsername("notifications")
            .withPassword("notifications");

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static NotificationRepository repository;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        String jdbcUrl = MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/notifications")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        repository = new NotificationRepository(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_source_events");
    }

    @Test
    void createsAllowlistedProjectionAndReplaysAfterRestart() {
        String event = eventJson(2, id(900), id(2), true);

        NotificationConsumeResult created = consumer(repository).consume(event);
        NotificationConsumeResult replayed = consumer(repository).consume(event);

        assertThat(created.outcome()).isEqualTo(NotificationConsumeResult.Outcome.CREATED);
        assertThat(replayed.outcome()).isEqualTo(NotificationConsumeResult.Outcome.REPLAYED);
        assertThat(count("notification_source_events")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(1);
        var projection = jdbc.queryForMap("""
                SELECT recipient_user_id, type, message_key, route, source_event_type,
                       source_event_version, version,
                       JSON_UNQUOTE(JSON_EXTRACT(message_args_json, '$.orderId')) AS order_id,
                       TIMESTAMPDIFF(DAY, created_at, retention_until) AS retention_days
                FROM notifications
                """);
        assertThat(projection)
                .containsEntry("recipient_user_id", id(2))
                .containsEntry("type", "ORDER_CONFIRMED")
                .containsEntry("message_key", "ORDER_CONFIRMED_V1")
                .containsEntry("route", "/account")
                .containsEntry("source_event_type", "order.confirmed")
                .containsEntry("order_id", id(1));
        assertThat(((Number) projection.get("source_event_version")).longValue()).isEqualTo(2);
        assertThat(((Number) projection.get("version")).longValue()).isZero();
        assertThat(((Number) projection.get("retention_days")).longValue()).isEqualTo(180);
        String persisted = jdbc.queryForObject("""
                SELECT CONCAT(
                    recipient_user_id, '|', type, '|', message_key, '|', route, '|',
                    message_args_json, '|', source_event_type, '|', source_payload_hash
                ) FROM notifications
                """, String.class);
        assertThat(persisted).doesNotContain(
                "paymentIntentId", id(20), "checkoutId", id(10),
                "businessIds", id(100), "email", "address", "provider");
    }

    @Test
    void detectsHashConflictAndDurablyRejectsUnsupportedAndSupportedPoison() {
        var consumer = consumer(repository);
        String event = eventJson(2, id(901), id(2), true);
        consumer.consume(event);

        NotificationConsumeResult conflict = consumer.consume(
                event.replace("correlation-notification-1", "correlation-notification-2"));
        NotificationConsumeResult unsupported =
                consumer.consume(eventJson(1, id(902), id(2), false));
        NotificationConsumeResult poison =
                consumer.consume(eventJson(2, id(903), id(2), false));
        NotificationConsumeResult unidentifiedPoison = consumer.consume("{not-json}");

        assertThat(conflict.outcome()).isEqualTo(NotificationConsumeResult.Outcome.CONFLICT);
        assertThat(unsupported.outcome()).isEqualTo(NotificationConsumeResult.Outcome.REJECTED);
        assertThat(poison.outcome()).isEqualTo(NotificationConsumeResult.Outcome.POISON);
        assertThat(unidentifiedPoison.outcome()).isEqualTo(NotificationConsumeResult.Outcome.POISON);
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("notification_source_events")).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT outcome FROM notification_source_events
                WHERE state = 'REJECTED' ORDER BY source_event_id
                """, String.class)).containsExactly("REJECTED", "POISON");
    }

    @Test
    void concurrentConsumersCreateAtMostOneNotification() throws Exception {
        String event = eventJson(2, id(904), id(2), true);
        var first = consumer(repository);
        var second = consumer(repository);

        List<NotificationConsumeResult.Outcome> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> first.consume(event).outcome());
            var right = executor.submit(() -> second.consume(event).outcome());
            outcomes = List.of(
                    left.get(15, TimeUnit.SECONDS),
                    right.get(15, TimeUnit.SECONDS));
        }

        assertThat(outcomes).containsExactlyInAnyOrder(
                NotificationConsumeResult.Outcome.CREATED,
                NotificationConsumeResult.Outcome.REPLAYED);
        assertThat(count("notification_source_events")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void persistenceFailureRollsBackAndCleanRetryCompletes() {
        NotificationRepository failing = spy(repository);
        doThrow(new TransientDataAccessResourceException("simulated write outage"))
                .when(failing)
                .insertOrderConfirmed(any(), any(), any(), any(), any(), any(), any());
        String event = eventJson(2, id(905), id(2), true);

        NotificationConsumeResult first = consumer(failing).consume(event);
        assertThat(first.outcome()).isEqualTo(NotificationConsumeResult.Outcome.RETRY_REQUIRED);
        assertThat(count("notification_source_events")).isZero();
        assertThat(count("notifications")).isZero();

        NotificationConsumeResult retry = consumer(repository).consume(event);
        assertThat(retry.outcome()).isEqualTo(NotificationConsumeResult.Outcome.CREATED);
        assertThat(count("notification_source_events")).isEqualTo(1);
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void migrationUsesOwnedInnoDbUtf8mb4TablesAndRequiredIndexes() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND engine = 'InnoDB'
                  AND table_collation LIKE 'utf8mb4%'
                  AND table_name IN ('notification_source_events', 'notifications')
                ORDER BY table_name
                """, String.class)).containsExactly(
                "notification_source_events", "notifications");
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'notifications'
                """, String.class)).contains(
                "uk_notification_recipient_source_type",
                "idx_notification_recipient_created",
                "idx_notification_recipient_unread");
    }

    private OrderConfirmedNotificationConsumer consumer(NotificationRepository targetRepository) {
        return new OrderConfirmedNotificationConsumer(
                new NotificationConsumerProperties(
                        true,
                        "notification-service-order-confirmed-v2",
                        Duration.ofDays(180),
                        false),
                new OrderConfirmedEventParser(),
                targetRepository,
                new NotificationUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)),
                new NotificationMetrics(new SimpleMeterRegistry()),
                transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
