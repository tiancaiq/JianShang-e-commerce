package com.msb.ecom.order_service.repository;

import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutReleaseStatus;
import com.msb.ecom.order_service.model.CheckoutStatus;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.config.CheckoutPaymentProperties;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.service.BuyerIdentityClient;
import com.msb.ecom.order_service.service.CheckoutPaymentService;
import com.msb.ecom.order_service.service.PaymentIntentClient;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CheckoutRepositoryIntegrationTests {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orders")
            .withUsername("orders")
            .withPassword("orders");

    static {
        MYSQL.start();
    }

    private CheckoutRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new CheckoutRepository(jdbc);
    }

    @Test
    void migrationPreservesTutorialTableAndRoundTripsSnapshots() {
        repository.insert(checkout("01C00000000000000000000001", "01U00000000000000000000001", "1"));

        CheckoutAggregate stored = repository.findOwned(
                "01C00000000000000000000001",
                "01U00000000000000000000001").orElseThrow();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 't_orders'
                """, Integer.class)).isEqualTo(1);
        assertThat(stored.status()).isEqualTo(CheckoutStatus.RESERVING);
        assertThat(stored.total()).isEqualByComparingTo("25.0000");
        assertThat(stored.address().sourceVersion()).isEqualTo(2);
        assertThat(stored.items()).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("SKU-1");
            assertThat(item.storeName()).isEqualTo("Demo Store");
            assertThat(item.condition()).isEqualTo("NEW");
            assertThat(item.policyVersion()).isEqualTo("LOCAL_DEMO_V1");
        });
        assertThat(stored.policies()).singleElement()
                .satisfies(policy -> assertThat(policy.version()).isEqualTo("LOCAL_DEMO_V1"));
    }

    @Test
    void generatedActiveBuyerKeyPreventsConcurrentActiveCheckouts() {
        String buyerId = "01U00000000000000000000001";
        repository.insert(checkout("01C00000000000000000000001", buyerId, "1"));

        assertThatThrownBy(() ->
                repository.insert(checkout("01C00000000000000000000002", buyerId, "2")))
                .isInstanceOf(DuplicateKeyException.class);

        repository.markFailed("01C00000000000000000000001", "TEST_FAILURE", Instant.now());
        repository.insert(checkout("01C00000000000000000000002", buyerId, "2"));
        assertThat(repository.findActiveByBuyer(buyerId)).get()
                .extracting(CheckoutAggregate::id)
                .isEqualTo("01C00000000000000000000002");
    }

    @Test
    void idempotencyRecordHasOneScopedKeyAndKeepsCompletedResponse() {
        Instant now = Instant.parse("2026-07-19T12:00:00Z");
        String scope = "BUYER:CREATE_CHECKOUT:01U00000000000000000000001";
        String key = "checkout-create-3-address-1";
        repository.insert(checkout(
                "01C00000000000000000000001",
                "01U00000000000000000000001",
                "1"));
        repository.insertIdempotency(
                "01D00000000000000000000001",
                scope,
                key,
                "a".repeat(64),
                "CREATE_CHECKOUT",
                "01C00000000000000000000001",
                now,
                now.plusSeconds(604800));

        assertThat(repository.idempotency(scope, key)).get().satisfies(record -> {
            assertThat(record.state()).isEqualTo("IN_PROGRESS");
            assertThat(record.checkoutId()).isEqualTo("01C00000000000000000000001");
        });

        repository.completeIdempotency(scope, key, 201, "{\"id\":\"checkout\"}", now.plusSeconds(1));

        assertThat(repository.idempotency(scope, key)).get().satisfies(record -> {
            assertThat(record.state()).isEqualTo("COMPLETED");
            assertThat(record.httpStatus()).isEqualTo(201);
            assertThat(record.responseJson()).isEqualTo("{\"id\":\"checkout\"}");
        });
        assertThatThrownBy(() -> repository.insertIdempotency(
                "01D00000000000000000000002",
                scope,
                key,
                "a".repeat(64),
                "CREATE_CHECKOUT",
                "01C00000000000000000000001",
                now,
                now.plusSeconds(604800)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void concurrentCancelAndExpiryPermitOnlyOneTerminalTransition() {
        Instant now = Instant.parse("2026-07-19T12:00:00Z");
        String checkoutId = "01C00000000000000000000001";
        repository.insert(checkout(checkoutId, "01U00000000000000000000001", "1"));
        repository.markPending(checkoutId, "01R00000000000000000000001", "ACTIVE", 1, now);

        CompletableFuture<Integer> cancel = CompletableFuture.supplyAsync(() ->
                repository.cancel(checkoutId, now.plusSeconds(1)));
        CompletableFuture<Integer> expire = CompletableFuture.supplyAsync(() ->
                repository.expire(checkoutId, now.plusSeconds(1)));

        assertThat(cancel.join() + expire.join()).isEqualTo(1);
        assertThat(repository.find(checkoutId)).get()
                .extracting(CheckoutAggregate::status)
                .isIn(CheckoutStatus.CANCELLED, CheckoutStatus.EXPIRED);
    }

    @Test
    void purchasedCartWorkBecomesVisibleOnlyAfterCheckoutCompletes() {
        Instant now = Instant.parse("2026-07-19T12:00:00Z");
        String checkoutId = "01C00000000000000000000001";
        repository.insert(checkoutAt(checkoutId, "01U00000000000000000000001", "1", now));
        repository.insertCartReconciliation(
                checkoutId,
                "keycloak-subject-1",
                3,
                List.of(new CartStoredItem(
                        "01L00000000000000000000001",
                        2,
                        new BigDecimal("12.5000"),
                        "USD",
                        now.minusSeconds(60),
                        now.minusSeconds(30))),
                now);

        assertThat(repository.findPendingCartReconciliations(now, 20)).isEmpty();
        repository.markPending(checkoutId, "01R00000000000000000000001", "ACTIVE", 1, now);
        repository.markPaymentProcessing(checkoutId, now);
        repository.markCompleted(checkoutId, "COMMITTED", 2, now);

        assertThat(repository.findPendingCartReconciliations(now, 20)).singleElement()
                .satisfies(work -> {
                    assertThat(work.cartOwnerKey()).isEqualTo("keycloak-subject-1");
                    assertThat(work.cartVersion()).isEqualTo(3);
                    assertThat(work.lines()).singleElement()
                            .satisfies(line -> assertThat(line.lineIdentity())
                                    .isEqualTo(now.minusSeconds(30)));
                });
        assertThat(repository.markCartReconciled(checkoutId, now.plusSeconds(1))).isEqualTo(1);
        assertThat(repository.findPendingCartReconciliations(now.plusSeconds(1), 20)).isEmpty();
    }

    @Test
    void paymentAdapterReadsThePersistedPendingSnapshotWithoutClientOwnedAmounts() {
        Instant now = Instant.now();
        String checkoutId = "01C00000000000000000000001";
        String buyerId = "01U00000000000000000000001";
        repository.insert(checkoutAt(checkoutId, buyerId, "1", now));
        repository.markPending(
                checkoutId,
                "01R00000000000000000000001",
                "ACTIVE",
                1,
                now);
        CurrentActorProvider actors = () ->
                new CurrentActor("buyer-subject", "token", "buyer@example.test", "Buyer", true);
        BuyerIdentityClient buyers = new BuyerIdentityClient() {
            @Override
            public String resolveBuyer(String subject) {
                return buyerId;
            }

            @Override
            public BuyerAddress resolveAddress(String subject, String addressId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void requireCapability(String userId, String scope) {
                // This repository integration fixture represents an unrestricted buyer.
            }

            @Override
            public void requireBusinessCapabilities(java.util.Set<String> businessIds, String scope) {
                // This repository integration fixture represents unrestricted businesses.
            }
        };
        PaymentIntentClient payments = (key, correlationId, command) -> {
            assertThat(key).isEqualTo("payment-integration-key");
            assertThat(command.checkoutId()).isEqualTo(checkoutId);
            assertThat(command.checkoutVersion()).isEqualTo(1);
            assertThat(command.checkoutSnapshotHash()).isEqualTo("a".repeat(64));
            assertThat(command.buyerId()).isEqualTo(buyerId);
            assertThat(command.amount()).isEqualByComparingTo("25.0000");
            assertThat(command.currency()).isEqualTo("USD");
            assertThat(command.businessIds()).containsExactly("01B00000000000000000000001");
            return new PaymentIntentClient.PaymentIntent(
                    "01P00000000000000000000001",
                    command.checkoutId(),
                    command.checkoutVersion(),
                    command.buyerId(),
                    command.businessIds(),
                    command.amount(),
                    command.currency(),
                    "FAKE_LOCAL_DEMO_V1",
                    "internal-reference",
                    "REQUIRES_ACTION",
                    1,
                    command.expiresAt(),
                    new PaymentIntentClient.ProviderAction(
                            "FAKE_HOSTED_ACTION",
                            "fake-action-reference"),
                    null);
        };
        CheckoutPaymentService service = new CheckoutPaymentService(
                actors,
                buyers,
                repository,
                new CheckoutPaymentBindingRepository(
                        jdbc,
                        new ObjectMapper().findAndRegisterModules()),
                new CheckoutProperties(
                        new MockEnvironment(),
                        true,
                        "LOCAL_DEMO",
                        "ZERO_LOCAL_DEMO_V1",
                        "FREE_LOCAL_DEMO_V1",
                        "LOCAL_DEMO_V1",
                        Duration.ofMinutes(15),
                        true,
                        50,
                        50,
                        Duration.ofDays(7)),
                new CheckoutPaymentProperties(
                        true,
                        "http://payment.test",
                        "payment-test-token",
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2)),
                payments,
                mock(OrderConfirmationRepository.class));

        var response = service.create(
                checkoutId,
                "payment-integration-key",
                "correlation-payment-integration");

        assertThat(response.checkoutId()).isEqualTo(checkoutId);
        assertThat(response.amount()).isEqualByComparingTo("25.0000");
        assertThat(response.action().type()).isEqualTo("FAKE_HOSTED_ACTION");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM checkout_payment_intents
                WHERE payment_intent_id = ? AND checkout_id = ? AND buyer_id = ?
                """, Integer.class,
                "01P00000000000000000000001",
                checkoutId,
                buyerId)).isEqualTo(1);
    }

    private CheckoutAggregate checkout(String checkoutId, String buyerId, String suffix) {
        return checkoutAt(
                checkoutId,
                buyerId,
                suffix,
                Instant.parse("2026-07-19T12:00:00Z"));
    }

    private CheckoutAggregate checkoutAt(
            String checkoutId,
            String buyerId,
            String suffix,
            Instant now) {
        String policyId = "01P0000000000000000000000" + suffix;
        String itemId = "01I0000000000000000000000" + suffix;
        String shippingId = "01S0000000000000000000000" + suffix;
        String taxId = "01T0000000000000000000000" + suffix;
        return new CheckoutAggregate(
                checkoutId,
                buyerId,
                CheckoutStatus.RESERVING,
                0,
                3,
                "a".repeat(64),
                "USD",
                new BigDecimal("25.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("0.0000"),
                new BigDecimal("25.0000"),
                now.plusSeconds(900),
                null,
                null,
                null,
                CheckoutReleaseStatus.NOT_REQUIRED,
                null,
                now,
                now,
                new CheckoutAggregate.Address(
                        "01A00000000000000000000001",
                        2,
                        "Home",
                        "Buyer",
                        "+15550123456",
                        "1 Main St",
                        null,
                        "Irvine",
                        "CA",
                        "92618",
                        "US"),
                List.of(new CheckoutAggregate.Item(
                        itemId,
                        1,
                        "01L00000000000000000000001",
                        "01B00000000000000000000001",
                        "01S00000000000000000000001",
                        "Demo Store",
                        7,
                        "Store item",
                        "SKU-1",
                        "NEW",
                        null,
                        2,
                        new BigDecimal("12.5000"),
                        "USD",
                        new BigDecimal("25.0000"),
                        new BigDecimal("0.0000"),
                        new BigDecimal("0.0000"),
                        new BigDecimal("0.0000"),
                        new BigDecimal("25.0000"),
                        policyId,
                        "LOCAL_DEMO_V1")),
                List.of(new CheckoutAggregate.ShippingQuote(
                        shippingId,
                        "01B00000000000000000000001",
                        "01S00000000000000000000001",
                        "FREE_LOCAL_DEMO",
                        new BigDecimal("0.0000"),
                        "USD",
                        "FREE_LOCAL_DEMO_V1",
                        now)),
                new CheckoutAggregate.TaxQuote(
                        taxId,
                        new BigDecimal("0.0000"),
                        "USD",
                        "ZERO_LOCAL_DEMO_V1",
                        now),
                List.of(new CheckoutAggregate.Policy(
                        policyId,
                        "01B00000000000000000000001",
                        "01S00000000000000000000001",
                        "01KXQCHKPOLICYLOCALDEMOV10",
                        "PLATFORM_DEFAULT",
                        "LOCAL_DEMO_V1",
                        "Shipping text",
                        "Cancellation text",
                        "Return text")));
    }
}
