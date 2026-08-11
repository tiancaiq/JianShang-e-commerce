package com.msb.ecom.order_service.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.model.PurchasedCartReconciliation;
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
import java.util.Optional;

@Repository
public class RedisCartRepository implements CartRepository {

    private static final String LIMIT_MARKER = "__LIMIT__";
    private static final String NOT_FOUND_MARKER = "__NOT_FOUND__";
    private static final String VERSION_CONFLICT_MARKER = "__VERSION_CONFLICT__";
    private static final String IDEMPOTENCY_CONFLICT_MARKER = "__IDEMPOTENCY_CONFLICT__";
    private static final String MISSING_MARKER = "__MISSING__";

    private static final DefaultRedisScript<String> UPSERT_SCRIPT = new DefaultRedisScript<>("""
            local prior = redis.call('GET', KEYS[2])
            if prior then
              local command = cjson.decode(prior)
              if command.requestHash ~= ARGV[10] then
                return '__IDEMPOTENCY_CONFLICT__'
              end
              return command.response
            end
            local raw = redis.call('GET', KEYS[1])
            local cart
            if raw then
              cart = cjson.decode(raw)
            else
              cart = {version = 0, items = cjson.decode('[]')}
            end
            if tonumber(cart.version or 0) ~= tonumber(ARGV[9]) then
              return '__VERSION_CONFLICT__'
            end
            local found = false
            for _, item in ipairs(cart.items) do
              if item.listingId == ARGV[1] then
                item.quantity = tonumber(ARGV[2])
                item.observedPrice = tonumber(ARGV[3])
                item.currency = ARGV[4]
                item.updatedAt = ARGV[5]
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
                addedAt = ARGV[5],
                updatedAt = ARGV[5]
              })
            end
            cart.version = tonumber(cart.version or 0) + 1
            cart.expiresAt = ARGV[6]
            local encoded = cjson.encode(cart)
            redis.call('SET', KEYS[1], encoded, 'EX', tonumber(ARGV[7]))
            redis.call('SET', KEYS[2], cjson.encode({requestHash = ARGV[10], response = encoded}), 'EX', tonumber(ARGV[7]))
            return encoded
            """, String.class);

    private static final DefaultRedisScript<String> REPLACE_QUANTITY_SCRIPT = new DefaultRedisScript<>("""
            local prior = redis.call('GET', KEYS[2])
            if prior then
              local command = cjson.decode(prior)
              if command.requestHash ~= ARGV[7] then
                return '__IDEMPOTENCY_CONFLICT__'
              end
              return command.response
            end
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return '__NOT_FOUND__'
            end
            local cart = cjson.decode(raw)
            if tonumber(cart.version or 0) ~= tonumber(ARGV[6]) then
              return '__VERSION_CONFLICT__'
            end
            local found = false
            for _, item in ipairs(cart.items) do
              if item.listingId == ARGV[1] then
                item.quantity = tonumber(ARGV[2])
                item.updatedAt = ARGV[3]
                found = true
                break
              end
            end
            if not found then
              return '__NOT_FOUND__'
            end
            cart.version = tonumber(cart.version or 0) + 1
            cart.expiresAt = ARGV[4]
            local encoded = cjson.encode(cart)
            redis.call('SET', KEYS[1], encoded, 'EX', tonumber(ARGV[5]))
            redis.call('SET', KEYS[2], cjson.encode({requestHash = ARGV[7], response = encoded}), 'EX', tonumber(ARGV[5]))
            return encoded
            """, String.class);

    private static final DefaultRedisScript<String> RECONCILE_PURCHASED_SCRIPT = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return '__MISSING__'
            end
            local cart = cjson.decode(raw)
            local purchased = cjson.decode(ARGV[2])
            local exactVersion = tonumber(cart.version or 0) == tonumber(ARGV[1])
            local kept = cjson.decode('[]')
            local removed = false
            for _, item in ipairs(cart.items) do
              local purchasedLine = false
              for _, line in ipairs(purchased) do
                if item.listingId == line.listingId then
                  local identity = item.updatedAt or item.addedAt
                  if exactVersion or (line.lineIdentity and identity == line.lineIdentity) then
                    purchasedLine = true
                  end
                  break
                end
              end
              if purchasedLine then
                removed = true
              else
                table.insert(kept, item)
              end
            end
            if not removed then
              return raw
            end
            cart.items = kept
            cart.version = tonumber(cart.version or 0) + 1
            cart.expiresAt = ARGV[3]
            local encoded = cjson.encode(cart)
            redis.call('SET', KEYS[1], encoded, 'EX', tonumber(ARGV[4]))
            return encoded
            """, String.class);

    private static final DefaultRedisScript<String> REMOVE_SCRIPT = new DefaultRedisScript<>("""
            local prior = redis.call('GET', KEYS[2])
            if prior then
              local command = cjson.decode(prior)
              if command.requestHash ~= ARGV[5] then
                return '__IDEMPOTENCY_CONFLICT__'
              end
              return command.response
            end
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return '__NOT_FOUND__'
            end
            local cart = cjson.decode(raw)
            if tonumber(cart.version or 0) ~= tonumber(ARGV[4]) then
              return '__VERSION_CONFLICT__'
            end
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
            redis.call('SET', KEYS[2], cjson.encode({requestHash = ARGV[5], response = encoded}), 'EX', tonumber(ARGV[3]))
            return encoded
            """, String.class);

    private static final DefaultRedisScript<String> CLEAR_SCRIPT = new DefaultRedisScript<>("""
            local prior = redis.call('GET', KEYS[2])
            if prior then
              local command = cjson.decode(prior)
              if command.requestHash ~= ARGV[4] then
                return '__IDEMPOTENCY_CONFLICT__'
              end
              return command.response
            end
            local raw = redis.call('GET', KEYS[1])
            local cart
            if raw then
              cart = cjson.decode(raw)
            else
              cart = {version = 0, items = cjson.decode('[]')}
            end
            if tonumber(cart.version or 0) ~= tonumber(ARGV[3]) then
              return '__VERSION_CONFLICT__'
            end
            cart.version = tonumber(cart.version or 0) + 1
            cart.items = cjson.decode('[]')
            cart.expiresAt = ARGV[1]
            local encoded = cjson.encode(cart)
            redis.call('SET', KEYS[1], encoded, 'EX', tonumber(ARGV[2]))
            redis.call('SET', KEYS[2], cjson.encode({requestHash = ARGV[4], response = encoded}), 'EX', tonumber(ARGV[2]))
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

    @Override
    public Optional<CartDocument> replay(String userId, String idempotencyKey, String requestHash) {
        String raw = redis.opsForValue().get(idempotencyKey(userId, idempotencyKey));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            JsonNode command = objectMapper.readTree(raw);
            if (!requestHash.equals(command.path("requestHash").asText())) {
                throw new CartException(
                        HttpStatus.CONFLICT,
                        "CART_IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key was reused with a different cart mutation.");
            }
            return Optional.of(deserializeRequired(command.path("response").asText(null)));
        } catch (JsonProcessingException exception) {
            throw storageFailure();
        }
    }

    // Atomically creates or replaces an item and refreshes cart expiry.
    @Override
    public CartDocument upsert(
            String userId,
            CartStoredItem item,
            long expectedVersion,
            String idempotencyKey,
            String requestHash) {
        Instant expiresAt = clock.instant().plus(ttl);
        String raw = redis.execute(
                UPSERT_SCRIPT,
                List.of(key(userId), idempotencyKey(userId, idempotencyKey)),
                item.listingId(),
                String.valueOf(item.quantity()),
                item.observedPrice().toPlainString(),
                item.currency(),
                item.lineIdentity().toString(),
                expiresAt.toString(),
                String.valueOf(ttl.toSeconds()),
                String.valueOf(maxItems),
                String.valueOf(expectedVersion),
                requestHash);
        if (LIMIT_MARKER.equals(raw)) {
            throw new CartException(
                    HttpStatus.CONFLICT,
                    "CART_ITEM_LIMIT_EXCEEDED",
                    "The cart cannot contain more than " + maxItems + " distinct items.");
        }
        requireNoConflict(raw);
        return deserializeRequired(raw);
    }

    // Atomically replaces quantity while preserving the price observed on add.
    @Override
    public CartDocument replaceQuantity(
            String userId,
            String listingId,
            int quantity,
            long expectedVersion,
            String idempotencyKey,
            String requestHash) {
        Instant expiresAt = clock.instant().plus(ttl);
        Instant updatedAt = clock.instant();
        String raw = redis.execute(
                REPLACE_QUANTITY_SCRIPT,
                List.of(key(userId), idempotencyKey(userId, idempotencyKey)),
                listingId,
                String.valueOf(quantity),
                updatedAt.toString(),
                expiresAt.toString(),
                String.valueOf(ttl.toSeconds()),
                String.valueOf(expectedVersion),
                requestHash);
        requireFound(raw);
        requireNoConflict(raw);
        return deserializeRequired(raw);
    }

    @Override
    public CartDocument remove(
            String userId,
            String listingId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash) {
        Instant expiresAt = clock.instant().plus(ttl);
        String raw = redis.execute(
                REMOVE_SCRIPT,
                List.of(key(userId), idempotencyKey(userId, idempotencyKey)),
                listingId,
                expiresAt.toString(),
                String.valueOf(ttl.toSeconds()),
                String.valueOf(expectedVersion),
                requestHash);
        requireFound(raw);
        requireNoConflict(raw);
        return deserializeRequired(raw);
    }

    @Override
    public CartDocument clear(
            String userId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash) {
        Instant expiresAt = clock.instant().plus(ttl);
        String raw = redis.execute(
                CLEAR_SCRIPT,
                List.of(key(userId), idempotencyKey(userId, idempotencyKey)),
                expiresAt.toString(),
                String.valueOf(ttl.toSeconds()),
                String.valueOf(expectedVersion),
                requestHash);
        requireNoConflict(raw);
        return deserializeRequired(raw);
    }

    // Removes only cart lines that are unchanged since their checkout snapshot.
    @Override
    public CartDocument reconcilePurchased(PurchasedCartReconciliation reconciliation) {
        Instant expiresAt = clock.instant().plus(ttl);
        try {
            var lineArray = objectMapper.createArrayNode();
            reconciliation.lines().forEach(line -> lineArray.addObject()
                    .put("listingId", line.listingId())
                    .put("lineIdentity", line.lineIdentity().toString()));
            String lines = objectMapper.writeValueAsString(lineArray);
            String raw = redis.execute(
                    RECONCILE_PURCHASED_SCRIPT,
                    List.of(key(reconciliation.cartOwnerKey())),
                    String.valueOf(reconciliation.cartVersion()),
                    lines,
                    expiresAt.toString(),
                    String.valueOf(ttl.toSeconds()));
            return MISSING_MARKER.equals(raw) ? CartDocument.empty() : deserializeRequired(raw);
        } catch (JsonProcessingException exception) {
            throw storageFailure();
        }
    }

    private void requireFound(String raw) {
        if (NOT_FOUND_MARKER.equals(raw)) {
            throw new CartException(
                    HttpStatus.NOT_FOUND,
                    "CART_ITEM_NOT_FOUND",
                    "The cart item was not found.");
        }
    }

    private void requireNoConflict(String raw) {
        if (VERSION_CONFLICT_MARKER.equals(raw)) {
            throw new CartException(
                    HttpStatus.CONFLICT,
                    "CART_VERSION_CONFLICT",
                    "The cart changed. Reload the cart and try again.");
        }
        if (IDEMPOTENCY_CONFLICT_MARKER.equals(raw)) {
            throw new CartException(
                    HttpStatus.CONFLICT,
                    "CART_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was reused with a different cart mutation.");
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

    private String idempotencyKey(String userId, String idempotencyKey) {
        return "cart:v1:{" + userId + "}:idempotency:" + idempotencyKey;
    }
}
