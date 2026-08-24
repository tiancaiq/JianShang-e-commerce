package com.msb.ecom.payment_service.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Timestamp;
import java.time.Instant;

import static com.msb.ecom.payment_service.analytics.PaymentAnalyticsContracts.Granularity;
import static com.msb.ecom.payment_service.analytics.PaymentAnalyticsContracts.TrendMetric;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentAnalyticsRepositoryMySqlTests {
    private static final Instant FROM = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-23T00:00:00Z");
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("payment_analytics").withUsername("payments").withPassword("payments");

    private static JdbcTemplate jdbc;
    private static PaymentAnalyticsRepository repository;

    @BeforeAll
    static void migrateAndSeed() {
        MYSQL.start();
        String url = MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new PaymentAnalyticsRepository(jdbc);
        seed();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void exactAggregatesUseHalfOpenOutcomeWindowsAndIncludeReturnRefundSuccesses() {
        var payments = repository.payments(FROM, TO, true);
        assertThat(payments.paymentsSucceeded()).isEqualTo(1);
        assertThat(payments.paymentsFailed()).isEqualTo(1);
        assertThat(payments.paymentSuccessRatePercent()).isEqualByComparingTo("50.00");
        assertThat(payments.succeededPaymentAmounts()).singleElement().satisfies(amount ->
                assertThat(amount.amount()).isEqualByComparingTo("100.0000"));
        assertThat(repository.payments(FROM, TO, false).succeededPaymentAmounts()).isNull();
        assertThat(repository.payments(TO.plusSeconds(1), TO.plusSeconds(2), false)
                .paymentSuccessRatePercent()).isNull();

        var refunds = repository.refunds(FROM, TO, true);
        assertThat(refunds.refundsRequested()).isEqualTo(3);
        assertThat(refunds.refundsSucceeded()).isEqualTo(2);
        assertThat(refunds.refundsFailed()).isEqualTo(1);
        assertThat(refunds.fullRefundsRequested()).isEqualTo(1);
        assertThat(refunds.partialRefundsRequested()).isEqualTo(2);
        assertThat(refunds.fullRefundsSucceeded()).isEqualTo(1);
        assertThat(refunds.partialRefundsFailed()).isEqualTo(1);
        assertThat(refunds.returnRefundsSucceeded()).isEqualTo(1);
        assertThat(refunds.amountMetrics()).singleElement().satisfies(amount -> {
            assertThat(amount.requestedAmount()).isEqualByComparingTo("22.0000");
            assertThat(amount.succeededAmount()).isEqualByComparingTo("13.0000");
            assertThat(amount.failedAmount()).isEqualByComparingTo("5.0000");
        });

        var backlog = repository.refundBacklog(true);
        assertThat(backlog.pendingCount()).isEqualTo(1);
        assertThat(backlog.processingCount()).isEqualTo(1);
        assertThat(backlog.oldestActiveCreatedAt()).isEqualTo(FROM.minusSeconds(10));
        assertThat(backlog.activeAmounts()).singleElement().satisfies(amount ->
                assertThat(amount.amount()).isEqualByComparingTo("15.0000"));

        assertThat(repository.trend(TrendMetric.PAYMENTS_SUCCEEDED, Granularity.DAY, FROM, TO))
                .extracting(PaymentAnalyticsContracts.TrendPoint::value).containsExactly(1L);
        assertThat(repository.trend(TrendMetric.REFUNDS_SUCCEEDED, Granularity.DAY, FROM, TO))
                .extracting(PaymentAnalyticsContracts.TrendPoint::value).containsExactly(2L);
    }

    private static void seed() {
        payment(1, "100.0000");
        payment(2, "50.0000");
        payment(3, "30.0000");
        payment(4, "40.0000");
        paymentHistory(11, 1, "SUCCEEDED", FROM);
        paymentHistory(12, 2, "FAILED", TO.minusNanos(1_000));
        paymentHistory(13, 3, "SUCCEEDED", TO);
        paymentHistory(14, 4, "SUCCEEDED", FROM.minusSeconds(1));

        refund(21, 1, "FULL", "SUCCEEDED", "10.0000", FROM, FROM.plusSeconds(1));
        refundHistory(31, 21, "SUCCEEDED", FROM.plusSeconds(1));
        refund(22, 2, "PARTIAL", "FAILED", "5.0000", TO.minusSeconds(1), null);
        refundHistory(32, 22, "FAILED", TO.minusNanos(1_000));
        refund(23, 3, "FULL", "SUCCEEDED", "8.0000", TO, TO);
        refundHistory(33, 23, "SUCCEEDED", TO);
        refund(24, 1, "PARTIAL", "PROCESSING", "8.0000", FROM.minusSeconds(10), null);
        refund(25, 2, "PARTIAL", "PENDING", "7.0000", FROM.plusSeconds(2), null);

        returnRefund(41, 1, "3.0000", FROM.plusSeconds(2));
        returnRefund(42, 2, "4.0000", TO);
    }

    private static void payment(int value, String amount) {
        Instant created = FROM.minusSeconds(100);
        jdbc.update("""
                insert into payment_intents (
                    id,checkout_id,checkout_version,checkout_snapshot_hash,buyer_id,caller_scope,
                    amount,currency,payment_method_type,capture_method,merchant_of_record,funds_flow,
                    provider,status,expires_at,created_at,updated_at
                ) values (?,?,1,?,?, 'CHECKOUT_SERVICE',?,'USD','CARD','AUTOMATIC','PLATFORM',
                          'SEPARATE_CHARGE_TRANSFER','FAKE_LOCAL_DEMO_V1','SUCCEEDED',?,?,?)
                """, id(1000 + value), id(2000 + value), "a".repeat(64), id(3000 + value), amount,
                ts(TO.plusSeconds(3600)), ts(created), ts(created));
    }

    private static void paymentHistory(int historyValue, int paymentValue, String status, Instant createdAt) {
        jdbc.update("""
                insert into payment_status_history (
                    id,payment_intent_id,from_status,to_status,reason_code,actor_scope,correlation_id,created_at
                ) values (?,?,null,?,'ANALYTICS_TEST','SYSTEM',?,?)
                """, id(4000 + historyValue), id(1000 + paymentValue), status,
                "analytics-payment-" + historyValue, ts(createdAt));
    }

    private static void refund(int value, int paymentValue, String type, String status,
                               String amount, Instant createdAt, Instant completedAt) {
        jdbc.update("""
                insert into payment_refunds (
                    id,payment_intent_id,order_id,idempotency_key,amount,currency,provider,
                    status,refund_type,created_at,updated_at,completed_at
                ) values (?,?,?, ?,?,'USD','FAKE_LOCAL_DEMO_V1',?,?,?, ?,?)
                """, id(5000 + value), id(1000 + paymentValue), id(6000 + value), "refund-" + value,
                amount, status, type, ts(createdAt), ts(createdAt),
                completedAt == null ? null : ts(completedAt));
    }

    private static void refundHistory(int historyValue, int refundValue, String status, Instant createdAt) {
        jdbc.update("""
                insert into payment_refund_status_history (
                    id,refund_id,from_status,to_status,reason_code,correlation_id,created_at
                ) values (?,?,null,?,'ANALYTICS_TEST',?,?)
                """, id(7000 + historyValue), id(5000 + refundValue), status,
                "analytics-refund-" + historyValue, ts(createdAt));
    }

    private static void returnRefund(int value, int paymentValue, String amount, Instant completedAt) {
        jdbc.update("""
                insert into payment_return_refunds (
                    id,payment_intent_id,return_id,order_id,business_order_id,idempotency_key,
                    amount,currency,provider,provider_reference,status,completed_at
                ) values (?,?,?,?,?,?,?,'USD','FAKE_LOCAL_DEMO_V1',?,'SUCCEEDED',?)
                """, id(8000 + value), id(1000 + paymentValue), id(9000 + value), id(10000 + value),
                id(11000 + value), "return-" + value, amount, "provider-" + value, ts(completedAt));
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static String id(int value) { return "01" + String.format("%024d", value); }
}
