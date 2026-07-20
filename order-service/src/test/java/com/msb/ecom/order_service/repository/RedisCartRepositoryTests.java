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

import static org.assertj.core.api.Assertions.assertThat;

class RedisCartRepositoryTests {

    private static final String USER_ID = "01U00000000000000000000001";
    private static final String LISTING_ID = "01L00000000000000000000001";
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
        RedisCartRepository repository = new RedisCartRepository(
                template,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(1),
                50);

        var added = repository.upsert(USER_ID, new CartStoredItem(
                LISTING_ID,
                2,
                new BigDecimal("4.50"),
                "USD",
                NOW));
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
}
