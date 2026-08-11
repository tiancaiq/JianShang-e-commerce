package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.model.PurchasedCartReconciliation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCartRepositoryTests {

    private static final String USER_ID = "01U00000000000000000000001";
    private static final String OTHER_USER_ID = "01U00000000000000000000002";
    private static final String LISTING_ID = "01L00000000000000000000001";
    private static final String OTHER_LISTING_ID = "01L00000000000000000000002";
    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @Test
    void mutationsAreAtomicVersionedAndExpiring() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();

        var added = repository.upsert(USER_ID, item(LISTING_ID, 2, "4.50"), 0, "cart-add-001", "add-hash-001");
        assertThat(added.version()).isEqualTo(1);
        assertThat(added.items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
        assertThat(template.getExpire("cart:v1:{" + USER_ID + "}")).isBetween(3590L, 3600L);

        var updated = repository.replaceQuantity(USER_ID, LISTING_ID, 3, 1, "cart-update-001", "update-hash-001");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.items().get(0).quantity()).isEqualTo(3);
        assertThat(updated.items().get(0).observedPrice()).isEqualByComparingTo("4.50");

        var removed = repository.remove(USER_ID, LISTING_ID, 2, "cart-remove-001", "remove-hash-001");
        assertThat(removed.version()).isEqualTo(3);
        assertThat(removed.items()).isEmpty();

        var cleared = repository.clear(USER_ID, 3, "cart-clear-001", "clear-hash-001");
        assertThat(cleared.version()).isEqualTo(4);
        assertThat(cleared.items()).isEmpty();
    }

    @Test
    void concurrentMutationsAreAtomicVersionedAndIsolated() throws Exception {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();
        repository.upsert(USER_ID, item(LISTING_ID, 1, "4.50"), 0, "cart-seed-001", "seed-hash-001");
        repository.upsert(
                OTHER_USER_ID,
                item(OTHER_LISTING_ID, 2, "8.00"),
                0,
                "cart-other-seed-001",
                "other-seed-hash-001");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            List<Callable<Object>> mutations = List.of(
                    () -> replaceAfterStart(repository, ready, start, 4, "cart-concurrent-004", "concurrent-hash-004"),
                    () -> replaceAfterStart(repository, ready, start, 7, "cart-concurrent-007", "concurrent-hash-007"));
            var futures = mutations.stream().map(executor::submit).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            List<Object> outcomes = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            return exception.getCause() == null ? exception : exception.getCause();
                        }
                    })
                    .toList();
            long successfulMutations = outcomes.stream()
                    .filter(outcome -> outcome instanceof CartDocument)
                    .count();

            assertThat(successfulMutations).isEqualTo(1);
            assertThat(outcomes)
                    .anySatisfy(outcome -> assertThat(outcome)
                            .isInstanceOfSatisfying(
                                    CartDocument.class,
                                    cart -> assertThat(cart.version()).isEqualTo(2)))
                    .anySatisfy(outcome -> assertThat(outcome)
                            .isInstanceOfSatisfying(
                                    CartException.class,
                                    exception -> assertThat(exception.code()).isEqualTo("CART_VERSION_CONFLICT")));

            var stored = repository.get(USER_ID);
            assertThat(stored.version()).isEqualTo(2);
            assertThat(stored.items()).singleElement()
                    .satisfies(item -> assertThat(item.quantity()).isIn(4, 7));
            assertThat(repository.get(OTHER_USER_ID).version()).isEqualTo(1);
            assertThat(repository.get(OTHER_USER_ID).items()).singleElement()
                    .satisfies(item -> {
                        assertThat(item.listingId()).isEqualTo(OTHER_LISTING_ID);
                        assertThat(item.quantity()).isEqualTo(2);
                    });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameListingRetryIsDeterministicAndVersioned() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();
        CartStoredItem originalCommand = item(LISTING_ID, 2, "4.50");

        var first = repository.upsert(USER_ID, originalCommand, 0, "cart-retry-001", "retry-hash-001");
        var replay = repository.upsert(USER_ID, originalCommand, 0, "cart-retry-001", "retry-hash-001");

        assertThat(replay).isEqualTo(first);
        assertThat(repository.get(USER_ID).version()).isEqualTo(1);
        assertThat(repository.get(USER_ID).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.quantity()).isEqualTo(2);
                    assertThat(item.observedPrice()).isEqualByComparingTo("4.50");
                });

        assertThatThrownBy(() -> repository.upsert(USER_ID, item(LISTING_ID, 3, "4.50"), 0, "cart-retry-001", "retry-hash-002"))
                .isInstanceOfSatisfying(
                        CartException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CART_IDEMPOTENCY_CONFLICT"));
        assertThat(repository.get(USER_ID).version()).isEqualTo(1);
        assertThat(repository.get(USER_ID).items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
    }

    @Test
    void sameIdempotencyKeyIsIsolatedByBuyer() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();

        var firstBuyer = repository.upsert(
                USER_ID,
                item(LISTING_ID, 2, "4.50"),
                0,
                "cart-shared-key-001",
                "shared-hash-001");
        var secondBuyer = repository.upsert(
                OTHER_USER_ID,
                item(OTHER_LISTING_ID, 5, "8.00"),
                0,
                "cart-shared-key-001",
                "shared-hash-001");

        assertThat(firstBuyer.version()).isEqualTo(1);
        assertThat(secondBuyer.version()).isEqualTo(1);
        assertThat(repository.get(USER_ID).items()).singleElement()
                .satisfies(item -> assertThat(item.listingId()).isEqualTo(LISTING_ID));
        assertThat(repository.get(OTHER_USER_ID).items()).singleElement()
                .satisfies(item -> assertThat(item.listingId()).isEqualTo(OTHER_LISTING_ID));
    }

    @Test
    void failedStaleVersionRequestCanRetryWithoutPoisonedIdempotencyState() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();
        repository.upsert(USER_ID, item(LISTING_ID, 1, "4.50"), 0, "cart-seed-002", "seed-hash-002");

        assertThatThrownBy(() -> repository.replaceQuantity(
                USER_ID,
                LISTING_ID,
                3,
                0,
                "cart-failed-retry-001",
                "failed-hash-001"))
                .isInstanceOfSatisfying(
                        CartException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CART_VERSION_CONFLICT"));
        assertThat(repository.replay(USER_ID, "cart-failed-retry-001", "failed-hash-001")).isEmpty();
        assertThat(repository.get(USER_ID).version()).isEqualTo(1);

        var retried = repository.replaceQuantity(
                USER_ID,
                LISTING_ID,
                3,
                1,
                "cart-failed-retry-001",
                "corrected-hash-001");

        assertThat(retried.version()).isEqualTo(2);
        assertThat(retried.items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(3));
    }

    @Test
    void cartAndCompletedMutationExpireTogether() throws Exception {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository(Duration.ofSeconds(1));
        repository.upsert(USER_ID, item(LISTING_ID, 2, "4.50"), 0, "cart-expiry-001", "expiry-hash-001");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (repository.get(USER_ID).version() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertThat(repository.get(USER_ID)).isEqualTo(CartDocument.empty());
        assertThat(repository.replay(USER_ID, "cart-expiry-001", "expiry-hash-001")).isEmpty();
    }

    @Test
    void purchasedReconciliationIsAtomicAndIdempotent() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();
        repository.upsert(USER_ID, item(LISTING_ID, 1, "4.50"), 0, "cart-purchase-001", "purchase-001");
        CartDocument snapshot = repository.upsert(
                USER_ID, item(OTHER_LISTING_ID, 1, "8.00"), 1, "cart-purchase-002", "purchase-002");
        var reconciliation = new PurchasedCartReconciliation(
                "01C00000000000000000000001",
                USER_ID,
                snapshot.version(),
                snapshot.items().stream()
                        .map(line -> new PurchasedCartReconciliation.Line(
                                line.listingId(), line.lineIdentity()))
                        .toList());

        CartDocument reconciled = repository.reconcilePurchased(reconciliation);
        CartDocument replayed = repository.reconcilePurchased(reconciliation);

        assertThat(reconciled.version()).isEqualTo(3);
        assertThat(reconciled.items()).isEmpty();
        assertThat(replayed).isEqualTo(reconciled);
    }

    @Test
    void reconciliationRemovesUntouchedPurchasedLinesAndPreservesNewerCartActivity() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();
        CartDocument snapshot = repository.upsert(
                USER_ID, item(LISTING_ID, 1, "4.50"), 0, "cart-snapshot-001", "snapshot-001");
        repository.upsert(
                USER_ID, item(OTHER_LISTING_ID, 2, "8.00"), 1, "cart-newer-001", "newer-001");

        CartDocument reconciled = repository.reconcilePurchased(new PurchasedCartReconciliation(
                "01C00000000000000000000001",
                USER_ID,
                snapshot.version(),
                List.of(new PurchasedCartReconciliation.Line(
                        LISTING_ID, snapshot.items().get(0).lineIdentity()))));

        assertThat(reconciled.version()).isEqualTo(3);
        assertThat(reconciled.items()).singleElement().satisfies(line -> {
            assertThat(line.listingId()).isEqualTo(OTHER_LISTING_ID);
            assertThat(line.quantity()).isEqualTo(2);
        });
    }

    @Test
    void reconciliationPreservesAPurchasedLineChangedAfterCheckoutStarted() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        CartDocument snapshot = repository().upsert(
                USER_ID, item(LISTING_ID, 1, "4.50"), 0, "cart-before-001", "before-001");
        RedisCartRepository laterRepository = repositoryAt(NOW.plusSeconds(1), Duration.ofHours(1));
        laterRepository.replaceQuantity(
                USER_ID, LISTING_ID, 3, 1, "cart-after-001", "after-001");

        CartDocument reconciled = laterRepository.reconcilePurchased(new PurchasedCartReconciliation(
                "01C00000000000000000000001",
                USER_ID,
                snapshot.version(),
                List.of(new PurchasedCartReconciliation.Line(
                        LISTING_ID, snapshot.items().get(0).lineIdentity()))));

        assertThat(reconciled.version()).isEqualTo(2);
        assertThat(reconciled.items()).singleElement()
                .satisfies(line -> assertThat(line.quantity()).isEqualTo(3));
    }

    private static Object replaceAfterStart(
            RedisCartRepository repository,
            CountDownLatch ready,
            CountDownLatch start,
            int quantity,
            String idempotencyKey,
            String requestHash) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository.replaceQuantity(USER_ID, LISTING_ID, quantity, 1, idempotencyKey, requestHash);
    }

    private static RedisCartRepository repository() {
        return repository(Duration.ofHours(1));
    }

    private static RedisCartRepository repository(Duration ttl) {
        return repositoryAt(NOW, ttl);
    }

    private static RedisCartRepository repositoryAt(Instant now, Duration ttl) {
        return new RedisCartRepository(
                template,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(now, ZoneOffset.UTC),
                ttl,
                50);
    }

    private static CartStoredItem item(String listingId, int quantity, String price) {
        return new CartStoredItem(
                listingId,
                quantity,
                new BigDecimal(price),
                "USD",
                NOW);
    }
}
