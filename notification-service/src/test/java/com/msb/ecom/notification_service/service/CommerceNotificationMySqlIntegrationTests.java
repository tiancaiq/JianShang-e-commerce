package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.notification_service.model.CommerceNotificationEvent;
import com.msb.ecom.notification_service.model.NotificationConsumeResult;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceNotificationMySqlIntegrationTests {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("notifications").withUsername("notifications").withPassword("notifications");
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static NotificationRepository repository;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        String url = MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/notifications").load().migrate();
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
    void replayIsIdempotentWhileRecipientsAndTypesRemainIndependent() {
        CommerceNotificationEvent event = confirmedEvent("Harbor Cart Supply");

        NotificationConsumeResult first = consumer().consume(event);
        NotificationConsumeResult replay = consumer().consume(event);

        assertThat(first.outcome()).isEqualTo(NotificationConsumeResult.Outcome.CREATED);
        assertThat(replay.outcome()).isEqualTo(NotificationConsumeResult.Outcome.REPLAYED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_source_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT recipient_scope_type, recipient_scope_id, type FROM notifications"))
                .extracting(row -> row.get("type"))
                .containsExactlyInAnyOrder("BUYER_ORDER_CONFIRMED", "SELLER_NEW_ORDER", "SELLER_NEW_ORDER");
    }

    @Test
    void changedReplayConflictsAndReadCommandsStayScopeIsolated() {
        assertThat(consumer().consume(confirmedEvent("Harbor Cart Supply")).outcome())
                .isEqualTo(NotificationConsumeResult.Outcome.CREATED);
        assertThat(consumer().consume(confirmedEvent("Changed Store")).outcome())
                .isEqualTo(NotificationConsumeResult.Outcome.CONFLICT);
        String buyerNotification = jdbc.queryForObject(
                "SELECT id FROM notifications WHERE recipient_scope_type='USER'", String.class);
        assertThat(repository.markScopeRead("BUSINESS", business(1), buyerNotification, Instant.now())).isFalse();
        assertThat(repository.unreadCount("USER", id(7))).isEqualTo(1);
        repository.markScopeAllRead("BUSINESS", business(1), Instant.now());
        assertThat(repository.unreadCount("USER", id(7))).isEqualTo(1);
        assertThat(repository.unreadCount("BUSINESS", business(1))).isZero();
    }

    private CommerceNotificationConsumer consumer() {
        return new CommerceNotificationConsumer(true, "notification-service-commerce-v1", Duration.ofDays(180),
                new ObjectMapper().findAndRegisterModules(), repository, new NotificationUlidGenerator(), transactions);
    }

    private CommerceNotificationEvent confirmedEvent(String storeName) {
        return new CommerceNotificationEvent(id(1), "order.confirmed", 2,
                Instant.parse("2026-08-10T12:00:00Z"), "order-service", "ORDER", id(2),
                "correlation", id(3), List.of(
                    new CommerceNotificationEvent.Target("USER", id(7), "BUYER_ORDER_CONFIRMED", id(2), null, null, null),
                    new CommerceNotificationEvent.Target("BUSINESS", business(1), "SELLER_NEW_ORDER", id(2), business(1), id(8), storeName),
                    new CommerceNotificationEvent.Target("BUSINESS", business(2), "SELLER_NEW_ORDER", id(2), business(2), id(9), "Maple Goods")));
    }

    private static String id(int suffix) { return "01KAAAAAAAAAAAAAAAAAAAAAA" + suffix; }
    private static String business(int suffix) { return "01JBBBBBBBBBBBBBBBBBBBBBB" + suffix; }
}
