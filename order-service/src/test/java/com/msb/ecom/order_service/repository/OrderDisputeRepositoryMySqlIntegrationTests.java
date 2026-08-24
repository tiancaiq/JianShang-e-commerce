package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.Priority;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.Resolution;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.Status;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDisputeRepositoryMySqlIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final String BUYER = id(1);
    private static final String BUSINESS = id(2);
    private static final String CHECKOUT = id(3);
    private static final String ORDER = id(4);
    private static final String GROUP = id(5);
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders").withUsername("orders").withPassword("orders");

    private static JdbcTemplate jdbc;
    private static OrderDisputeRepository disputes;

    @BeforeAll
    static void migrate() {
        MYSQL.start();
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        var dataSource = new DriverManagerDataSource(url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        disputes = new OrderDisputeRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @BeforeEach
    void cleanAndSeed() {
        jdbc.update("DELETE FROM order_dispute_events");
        jdbc.update("DELETE FROM order_dispute_admin_notes");
        jdbc.update("DELETE FROM order_dispute_evidence");
        jdbc.update("DELETE FROM order_dispute_statements");
        jdbc.update("DELETE FROM order_dispute_items");
        jdbc.update("DELETE FROM order_dispute_commands");
        jdbc.update("DELETE FROM order_disputes");
        jdbc.update("DELETE FROM business_orders");
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM checkout_sessions");
        seedOrder();
    }

    @Test
    void concurrentClaimsAssignExactlyOneAdministrator() throws Exception {
        String disputeId = id(10);
        String adminA = id(11);
        String adminB = id(12);
        insertDispute(disputeId);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> tasks = List.of(
                    () -> disputes.claim(disputeId, 0, adminA, NOW),
                    () -> disputes.claim(disputeId, 0, adminB, NOW));
            int updated = executor.invokeAll(tasks).stream().mapToInt(future -> {
                try { return future.get(); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            }).sum();
            assertThat(updated).isEqualTo(1);
        }

        var claimed = disputes.find(disputeId, false).orElseThrow();
        assertThat(claimed.assignedAdminId()).isIn(adminA, adminB);
        assertThat(claimed.status()).isEqualTo(Status.UNDER_ADMIN_REVIEW);
        assertThat(claimed.version()).isEqualTo(1);
    }

    @Test
    void concurrentResolutionsCreateOneImmutableTerminalOutcome() throws Exception {
        String disputeId = id(20);
        String admin = id(21);
        insertDispute(disputeId);
        assertThat(disputes.claim(disputeId, 0, admin, NOW)).isEqualTo(1);
        assertThat(disputes.status(
                disputeId, 1, admin, Status.UNDER_ADMIN_REVIEW, Status.READY_FOR_DECISION, NOW))
                .isEqualTo(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> tasks = List.of(
                    () -> disputes.resolve(
                            disputeId, 2, admin, Resolution.RESOLVED_NO_ACTION,
                            "NO_REMEDY", "Evidence does not support remediation.",
                            null, null, null, NOW),
                    () -> disputes.resolve(
                            disputeId, 2, admin, Resolution.REFUND_RECOMMENDED,
                            "BUYER_REMEDY", "Evidence supports a full refund.",
                            new java.math.BigDecimal("40.0000"), null, null, NOW));
            int updated = executor.invokeAll(tasks).stream().mapToInt(future -> {
                try { return future.get(); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            }).sum();
            assertThat(updated).isEqualTo(1);
        }

        var resolved = disputes.find(disputeId, false).orElseThrow();
        assertThat(resolved.status()).isIn(Status.RESOLVED_NO_ACTION, Status.REFUND_RECOMMENDED);
        assertThat(resolved.resolution()).isIn(
                Resolution.RESOLVED_NO_ACTION, Resolution.REFUND_RECOMMENDED);
        assertThat(resolved.version()).isEqualTo(3);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(disputes.release(disputeId, 3, admin, NOW)).isZero();
        assertThat(disputes.touchParticipant(disputeId, 3, NOW)).isZero();
        assertThat(disputes.resolve(
                disputeId, 3, admin, Resolution.RESOLVED_NO_ACTION,
                "NO_REMEDY", "Second terminal outcome", null, null, null, NOW)).isZero();
    }

    private void insertDispute(String id) {
        disputes.insert(new OrderDisputeRepository.DisputeRow(
                id, ORDER, GROUP, BUSINESS, BUYER, "BUYER", BUYER,
                "ITEM_NOT_AS_DESCRIBED", "Concurrent dispute regression", Status.OPEN,
                Priority.MEDIUM, null, null, null, null, null, "USD",
                null, null, NOW, NOW, null, 0, "corr-dispute-create"), List.of());
    }

    private void seedOrder() {
        jdbc.update("""
                INSERT INTO checkout_sessions (id,buyer_id,status,version,cart_version,cart_snapshot_hash,
                  currency,subtotal,shipping,tax,discount,total,expires_at,release_status,created_at,updated_at)
                VALUES (?,?,'COMPLETED',2,1,?,'USD',40,0,0,0,40,?,'COMPLETE',?,?)
                """, CHECKOUT, BUYER, "a".repeat(64), Timestamp.from(NOW.plusSeconds(900)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO orders (id,checkout_id,payment_intent_id,buyer_id,order_number,currency,
                  subtotal,shipping,tax,discount,total,payment_status,status,confirmed_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'USD',40,0,0,0,40,'SUCCEEDED','CONFIRMED',?,?,?)
                """, ORDER, CHECKOUT, id(6), BUYER, "MSB-CONCURRENCY",
                Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO business_orders (id,order_id,business_id,store_id,seller_order_number,
                  fulfillment_status,cancellation_status,subtotal,shipping,tax,discount,total,
                  platform_fee_projection,version,created_at,updated_at)
                VALUES (?,?,?,?,?,'DELIVERED','NONE',40,0,0,0,40,NULL,0,?,?)
                """, GROUP, ORDER, BUSINESS, id(7), "SELLER-CONCURRENCY",
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
