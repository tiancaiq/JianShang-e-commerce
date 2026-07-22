package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.model.CartStoredItem;
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

        var added = repository.upsert(USER_ID, item(LISTING_ID, 2, "4.50"));
        assertThat(added.version()).isEqualTo(1);
        assertThat(added.items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
        assertThat(template.getExpire("cart:v1:{" + USER_ID + "}")).isBetween(3590L, 3600L);

        var updated = repository.replaceQuantity(USER_ID, LISTING_ID, 3);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.items().get(0).quantity()).isEqualTo(3);
        assertThat(updated.items().get(0).observedPrice()).isEqualByComparingTo("4.50");

        var removed = repository.remove(USER_ID, LISTING_ID);
        assertThat(removed.version()).isEqualTo(3);
        assertThat(removed.items()).isEmpty();

        repository.clear(USER_ID);
        assertThat(repository.get(USER_ID).version()).isZero();
    }

    @Test
    void concurrentMutationsAreAtomicVersionedAndIsolated() throws Exception {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisCartRepository repository = repository();
        repository.upsert(USER_ID, item(LISTING_ID, 1, "4.50"));
        repository.upsert(OTHER_USER_ID, item(OTHER_LISTING_ID, 2, "8.00"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            List<Callable<Object>> mutations = List.of(
                    () -> replaceAfterStart(repository, ready, start, 4),
                    () -> replaceAfterStart(repository, ready, start, 7));
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
                    .filter(outcome -> outcome instanceof com.msb.ecom.order_service.model.CartDocument)
                    .count();

            assertThat(successfulMutations).isEqualTo(1);
            assertThat(outcomes)
                    .anySatisfy(outcome -> assertThat(outcome)
                            .isInstanceOfSatisfying(
                                    com.msb.ecom.order_service.model.CartDocument.class,
                                    cart -> assertThat(cart.version()).isEqualTo(2)))
                    .anySatisfy(outcome -> assertThat(outcome)
                            .isInstanceOfSatisfying(
                                    com.msb.ecom.order_service.model.CartException.class,
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

        var first = repository.upsert(USER_ID, originalCommand);
        var replay = repository.upsert(USER_ID, originalCommand);

        assertThat(replay).isEqualTo(first);
        assertThat(repository.get(USER_ID).version()).isEqualTo(1);
        assertThat(repository.get(USER_ID).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.quantity()).isEqualTo(2);
                    assertThat(item.observedPrice()).isEqualByComparingTo("4.50");
                });

        assertThatThrownBy(() -> repository.upsert(USER_ID, item(LISTING_ID, 3, "4.50")))
                .isInstanceOfSatisfying(
                        com.msb.ecom.order_service.model.CartException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CART_IDEMPOTENCY_CONFLICT"));
        assertThat(repository.get(USER_ID).version()).isEqualTo(1);
        assertThat(repository.get(USER_ID).items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(2));
    }

    private static Object replaceAfterStart(
            RedisCartRepository repository,
            CountDownLatch ready,
            CountDownLatch start,
            int quantity) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return repository.replaceQuantity(USER_ID, LISTING_ID, quantity);
    }

    private static RedisCartRepository repository() {
        return new RedisCartRepository(
                template,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
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
