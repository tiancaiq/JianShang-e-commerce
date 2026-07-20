package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.model.CartStoredItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class RedisCartRepository implements CartRepository {

    private static final String LIMIT_MARKER = "__LIMIT__";
    private static final String NOT_FOUND_MARKER = "__NOT_FOUND__";

    private static final DefaultRedisScript<String> UPSERT_SCRIPT = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            local cart
            if raw then
              cart = cjson.decode(raw)
            else
              cart = {version = 0, items = cjson.decode('[]')}
            end
            local found = false
            for _, item in ipairs(cart.items) do
              if item.listingId == ARGV[1] then
                item.quantity = tonumber(ARGV[2])
                item.observedPrice = tonumber(ARGV[3])
                item.currency = ARGV[4]
                found = true
                break
              end
            end
            if not found then
              if #cart.items >= tonumber(ARGV[8]) then
                return '__LIMIT__'
              end
              table.insert(cart.items, {
                listingId = ARGV[1],
                quantity = tonumber(ARGV[2]),
                observedPrice = tonumber(ARGV[3]),
                currency = ARGV[4],
                addedAt = ARGV[5]
              })
            end
            cart.version = tonumber(cart.version or 0) + 1
            cart.expiresAt = ARGV[6]
            local encoded = cjson.encode(cart)
            redis.call('SET', KEYS[1], encoded, 'EX', tonumber(ARGV[7]))
            return encoded
            """, String.class);

    private static final DefaultRedisScript<String> REPLACE_QUANTITY_SCRIPT = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return '__NOT_FOUND__'
            end
            local cart = cjson.decode(raw)
            local found = false
            for _, item in ipairs(cart.items) do
              if item.listingId == ARGV[1] then
                item.quantity = tonumber(ARGV[2])
                found = true
                break
              end
            end
            if not found then
              return '__NOT_FOUND__'
            end
            cart.version = tonumber(cart.version or 0) + 1
            cart.expiresAt = ARGV[3]
            local encoded = cjson.encode(cart)
            redis.call('SET', KEYS[1], encoded, 'EX', tonumber(ARGV[4]))
            return encoded
            """, String.class);

    private static final DefaultRedisScript<String> REMOVE_SCRIPT = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return '__NOT_FOUND__'
            end
            local cart = cjson.decode(raw)
            local kept = cjson.decode('[]')
            local found = false
            for _, item in ipairs(cart.items) do
              if item.listingId == ARGV[1] then
                found = true
              else
                table.insert(kept, item)
              end
            end
            if not found then
              return '__NOT_FOUND__'
            end
            cart.items = kept
            cart.version = tonumber(cart.version or 0) + 1
            cart.expiresAt = ARGV[2]
            local encoded = cjson.encode(cart)
            redis.call('SET', KEYS[1], encoded, 'EX', tonumber(ARGV[3]))
            return encoded
            """, String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration ttl;
    private final int maxItems;

    @Autowired
    public RedisCartRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${cart.ttl:30d}") Duration ttl,
            @Value("${cart.max-items:50}") int maxItems) {
        this(redis, objectMapper, Clock.systemUTC(), ttl, maxItems);
    }

    RedisCartRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Clock clock,
            Duration ttl,
            int maxItems) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ttl = ttl;
        this.maxItems = maxItems;
    }

    @Override
    public CartDocument get(String userId) {
        String raw = redis.opsForValue().get(key(userId));
        return raw == null ? CartDocument.empty() : deserialize(raw);
    }

    // Atomically creates or replaces an item and refreshes cart expiry.
    @Override
    public CartDocument upsert(String userId, CartStoredItem item) {
        Instant expiresAt = clock.instant().plus(ttl);
        String raw = redis.execute(
                UPSERT_SCRIPT,
                List.of(key(userId)),
                item.listingId(),
                String.valueOf(item.quantity()),
                item.observedPrice().toPlainString(),
                item.currency(),
                item.addedAt().toString(),
                expiresAt.toString(),
                String.valueOf(ttl.toSeconds()),
                String.valueOf(maxItems));
        if (LIMIT_MARKER.equals(raw)) {
            throw new CartException(
                    HttpStatus.CONFLICT,
                    "CART_ITEM_LIMIT_EXCEEDED",
                    "The cart cannot contain more than " + maxItems + " distinct items.");
        }
        return deserializeRequired(raw);
    }

    // Atomically replaces quantity while preserving the price observed on add.
    @Override
    public CartDocument replaceQuantity(String userId, String listingId, int quantity) {
        Instant expiresAt = clock.instant().plus(ttl);
        String raw = redis.execute(
                REPLACE_QUANTITY_SCRIPT,
                List.of(key(userId)),
                listingId,
                String.valueOf(quantity),
                expiresAt.toString(),
                String.valueOf(ttl.toSeconds()));
        requireFound(raw);
        return deserializeRequired(raw);
    }

    @Override
    public CartDocument remove(String userId, String listingId) {
        Instant expiresAt = clock.instant().plus(ttl);
        String raw = redis.execute(
                REMOVE_SCRIPT,
                List.of(key(userId)),
                listingId,
                expiresAt.toString(),
                String.valueOf(ttl.toSeconds()));
        requireFound(raw);
        return deserializeRequired(raw);
    }

    @Override
    public void clear(String userId) {
        redis.delete(key(userId));
    }

    private void requireFound(String raw) {
        if (NOT_FOUND_MARKER.equals(raw)) {
            throw new CartException(
                    HttpStatus.NOT_FOUND,
                    "CART_ITEM_NOT_FOUND",
                    "The cart item was not found.");
        }
    }

    private CartDocument deserializeRequired(String raw) {
        if (raw == null) {
            throw storageFailure();
        }
        return deserialize(raw);
    }

    private CartDocument deserialize(String raw) {
        try {
            JsonNode document = objectMapper.readTree(raw);
            JsonNode items = document.path("items");
            if (document instanceof ObjectNode objectNode && items.isObject() && items.isEmpty()) {
                objectNode.set("items", objectMapper.createArrayNode());
            }
            return objectMapper.treeToValue(document, CartDocument.class);
        } catch (JsonProcessingException exception) {
            throw storageFailure();
        }
    }

    private CartException storageFailure() {
        return new CartException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "CART_STORAGE_ERROR",
                "The cart could not be read or updated.");
    }

    private String key(String userId) {
        return "cart:v1:{" + userId + "}";
    }
}
