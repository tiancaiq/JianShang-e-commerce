package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.repository.NotificationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.msb.ecom.notification_service.service.NotificationCursorCodecTests.id;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationReadMySqlIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("notifications")
            .withUsername("notifications")
            .withPassword("notifications");

    private static JdbcTemplate jdbc;
    private static NotificationRepository repository;
    private static TransactionTemplate transactions;

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
        repository = new NotificationRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM notification_source_events");
    }

    @Test
    void recipientPageIsStableAcrossCreatedAtTiesAndConcurrentInsert() {
        insert(id(10), id(110), id(7), NOW);
        insert(id(11), id(111), id(7), NOW);
        insert(id(12), id(112), id(7), NOW.minusSeconds(1));
        insert(id(13), id(113), id(8), NOW.plusSeconds(1));

        var first = repository.findRecipientPage(id(7), null, null, 2);
        insert(id(14), id(114), id(7), NOW.plusSeconds(2));
        var second = repository.findRecipientPage(
                id(7), first.getLast().createdAt(), first.getLast().id(), 2);

        assertThat(first).extracting(row -> row.id()).containsExactly(id(11), id(10));
        assertThat(second).extracting(row -> row.id()).containsExactly(id(12));
    }

    @Test
    void markOnePreservesFirstTimestampAndHidesCrossRecipient() {
        insert(id(10), id(110), id(7), NOW);
        Instant firstRead = NOW.plusSeconds(10);

        assertThat(repository.markOwnedRead(id(7), id(10), firstRead)).isTrue();
        assertThat(repository.markOwnedRead(id(7), id(10), NOW.plusSeconds(20))).isTrue();
        assertThat(repository.markOwnedRead(id(8), id(10), NOW.plusSeconds(30))).isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE id = ?",
                Timestamp.class,
                id(10)).toInstant()).isEqualTo(firstRead);
    }

    @Test
    void readAllTouchesOnlyActorRowsAndRollsBackAtomically() {
        insert(id(10), id(110), id(7), NOW);
        insert(id(11), id(111), id(7), NOW.minusSeconds(1));
        insert(id(12), id(112), id(8), NOW.minusSeconds(2));

        transactions.executeWithoutResult(status -> {
            repository.markAllOwnedRead(id(7), NOW.plusSeconds(10));
            status.setRollbackOnly();
        });
        assertThat(unread(id(7))).isEqualTo(2);

        repository.markAllOwnedRead(id(7), NOW.plusSeconds(20));
        repository.markAllOwnedRead(id(7), NOW.plusSeconds(30));
        assertThat(unread(id(7))).isZero();
        assertThat(unread(id(8))).isEqualTo(1);
    }

    @Test
    void concurrentMarkOneKeepsOneTimestampAndBothCallsAreIdempotent() throws Exception {
        insert(id(10), id(110), id(7), NOW);
        Instant leftTime = NOW.plusSeconds(10);
        Instant rightTime = NOW.plusSeconds(20);

        List<Boolean> results;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> repository.markOwnedRead(id(7), id(10), leftTime));
            var right = executor.submit(() -> repository.markOwnedRead(id(7), id(10), rightTime));
            results = List.of(
                    left.get(15, TimeUnit.SECONDS),
                    right.get(15, TimeUnit.SECONDS));
        }

        assertThat(results).containsOnly(true);
        Instant persisted = jdbc.queryForObject(
                "SELECT read_at FROM notifications WHERE id = ?",
                Timestamp.class,
                id(10)).toInstant();
        assertThat(persisted).isIn(leftTime, rightTime);
    }

    private void insert(String notificationId, String eventId, String recipientId, Instant createdAt) {
        Instant retention = createdAt.plusSeconds(180L * 24 * 60 * 60);
        jdbc.update("""
                        INSERT INTO notification_source_events (
                            consumer_name, source_event_id, source_event_type,
                            source_event_version, source_payload_hash, state, outcome,
                            safe_error_code, source_occurred_at, correlation_id,
                            attempt_count, processed_at, retention_until, created_at, updated_at
                        ) VALUES ('notification-service-order-confirmed-v2', ?,
                                  'order.confirmed', 2, ?, 'COMPLETED', 'CREATED', NULL,
                                  ?, 'notification-read-mysql', 1, ?, ?, ?, ?)
                        """,
                eventId,
                "a".repeat(64),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(retention),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        jdbc.update("""
                        INSERT INTO notifications (
                            id, recipient_user_id, type, message_key, message_args_json,
                            route, source_consumer_name, source_event_id, source_event_type,
                            source_event_version, source_occurred_at, source_payload_hash,
                            read_at, version, retention_until, created_at, updated_at
                        ) VALUES (?, ?, 'ORDER_CONFIRMED', 'ORDER_CONFIRMED_V1',
                                  JSON_OBJECT('orderId', ?), '/account',
                                  'notification-service-order-confirmed-v2', ?,
                                  'order.confirmed', 2, ?, ?, NULL, 0, ?, ?, ?)
                        """,
                notificationId,
                recipientId,
                id(1),
                eventId,
                Timestamp.from(createdAt),
                "a".repeat(64),
                Timestamp.from(retention),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    private int unread(String recipientId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ? AND read_at IS NULL",
                Integer.class,
                recipientId);
    }
}
