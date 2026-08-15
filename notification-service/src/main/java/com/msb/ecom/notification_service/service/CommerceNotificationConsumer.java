package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.notification_service.model.CommerceNotificationEvent;
import com.msb.ecom.notification_service.model.NotificationConsumeResult;
import com.msb.ecom.notification_service.model.NotificationSourceEvent;
import com.msb.ecom.notification_service.model.NotificationSourceRecord;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CommerceNotificationConsumer {
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern BUSINESS_ID = Pattern.compile("[0-9A-Z]{26}");
    private static final Map<String, Set<String>> EVENT_TYPES = Map.ofEntries(
            Map.entry("return.requested", Set.of("SELLER_RETURN_REQUESTED")),
            Map.entry("return.authorized", Set.of("BUYER_RETURN_AUTHORIZED")),
            Map.entry("return.received", Set.of("BUYER_RETURN_RECEIVED")),
            Map.entry("return.refund_completed", Set.of("BUYER_RETURN_REFUND_COMPLETED")),
            Map.entry("order.confirmed", Set.of("BUYER_ORDER_CONFIRMED", "SELLER_NEW_ORDER")),
            Map.entry("business_order.accepted", Set.of("BUYER_ORDER_ACCEPTED")),
            Map.entry("business_order.processing_started", Set.of("BUYER_ORDER_PROCESSING")),
            Map.entry("business_order.shipped", Set.of("BUYER_ORDER_SHIPPED")),
            Map.entry("business_order.delivered_demo", Set.of("BUYER_ORDER_DELIVERED")),
            Map.entry("order.cancellation_completed", Set.of(
                    "BUYER_ORDER_CANCELLED", "BUYER_REFUND_COMPLETED", "SELLER_ORDER_CANCELLED")));

    private final boolean enabled;
    private final String consumerName;
    private final Duration retention;
    private final ObjectMapper mapper;
    private final NotificationRepository repository;
    private final NotificationUlidGenerator ids;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    public CommerceNotificationConsumer(
            @Value("${notifications.commerce-events.enabled:false}") boolean enabled,
            @Value("${notifications.commerce-events.consumer-name}") String consumerName,
            @Value("${notifications.commerce-events.retention:P180D}") Duration retention,
            ObjectMapper mapper,
            NotificationRepository repository,
            NotificationUlidGenerator ids,
            PlatformTransactionManager transactionManager) {
        this.enabled = enabled;
        this.consumerName = consumerName;
        this.retention = retention;
        this.mapper = mapper;
        this.repository = repository;
        this.ids = ids;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // Converts one authoritative commerce event into an allowlisted, retry-safe projection batch.
    public NotificationConsumeResult consume(CommerceNotificationEvent input) {
        if (!enabled) return result(NotificationConsumeResult.Outcome.DISABLED, "NOTIFICATION_CONSUMER_DISABLED");
        Validation validated;
        try {
            validated = validate(input);
        } catch (RuntimeException exception) {
            return result(NotificationConsumeResult.Outcome.POISON, "NOTIFICATION_EVENT_MALFORMED");
        }
        try {
            return Objects.requireNonNull(transactions.execute(status -> process(validated)));
        } catch (DuplicateKeyException exception) {
            return replay(validated.source(), validated.hash());
        } catch (DataAccessException exception) {
            return result(NotificationConsumeResult.Outcome.RETRY_REQUIRED,
                    "NOTIFICATION_PERSISTENCE_RETRY_REQUIRED");
        }
    }

    private NotificationConsumeResult process(Validation value) {
        NotificationSourceEvent source = value.source();
        NotificationSourceRecord existing = repository.lockSource(consumerName, source.eventId()).orElse(null);
        if (existing != null) return replay(existing, value.hash());
        Instant now = clock.instant();
        Instant retentionUntil = now.plus(retention);
        repository.insertProcessing(consumerName, source, value.hash(), retentionUntil, now);
        for (CommerceNotificationEvent.Target target : value.input().targets()) {
            String messageKey = target.notificationType() + "_V1";
            String route = "USER".equals(target.scopeType())
                    ? "/account/orders/" + target.orderId()
                    : "/seller/orders/" + target.businessOrderId();
            repository.insertCommerceNotification(
                    ids.next(), consumerName, source, target.scopeType(), target.scopeId(),
                    target.notificationType(), messageKey, target.orderId(), target.businessId(),
                    target.businessOrderId(), target.storeDisplayName(), route, value.hash(),
                    retentionUntil, now);
        }
        repository.complete(consumerName, source.eventId(), "CREATED", null, now);
        return result(NotificationConsumeResult.Outcome.CREATED, null);
    }

    private NotificationConsumeResult replay(NotificationSourceEvent source, String hash) {
        NotificationSourceRecord existing = repository.findSource(consumerName, source.eventId()).orElse(null);
        if (existing == null) return result(NotificationConsumeResult.Outcome.RETRY_REQUIRED,
                "NOTIFICATION_PERSISTENCE_RETRY_REQUIRED");
        return replay(existing, hash);
    }

    private NotificationConsumeResult replay(NotificationSourceRecord existing, String hash) {
        if (!MessageDigest.isEqual(existing.payloadHash().getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII))) {
            return result(NotificationConsumeResult.Outcome.CONFLICT, "NOTIFICATION_SOURCE_EVENT_CONFLICT");
        }
        if ("COMPLETED".equals(existing.state())) return result(NotificationConsumeResult.Outcome.REPLAYED, null);
        return result(NotificationConsumeResult.Outcome.RETRY_REQUIRED,
                "NOTIFICATION_PERSISTENCE_RETRY_REQUIRED");
    }

    private Validation validate(CommerceNotificationEvent input) {
        if (input == null || !ULID.matcher(required(input.eventId())).matches()
                || !EVENT_TYPES.containsKey(input.eventType()) || input.eventVersion() < 1
                || input.occurredAt() == null || !"order-service".equals(input.producer())
                || !ULID.matcher(required(input.aggregateId())).matches()
                || input.targets() == null || input.targets().isEmpty() || input.targets().size() > 100) {
            throw new IllegalArgumentException();
        }
        Set<String> permitted = EVENT_TYPES.get(input.eventType());
        for (CommerceNotificationEvent.Target target : input.targets()) {
            boolean user = "USER".equals(target.scopeType());
            boolean business = "BUSINESS".equals(target.scopeType());
            if ((!user && !business) || !permitted.contains(target.notificationType())
                    || !ULID.matcher(required(target.orderId())).matches()
                    || (user && !ULID.matcher(required(target.scopeId())).matches())
                    || (business && !BUSINESS_ID.matcher(required(target.scopeId())).matches())
                    || (business && !target.scopeId().equals(target.businessId()))
                    || (target.businessOrderId() != null
                        && !ULID.matcher(target.businessOrderId()).matches())
                    || (business && target.businessOrderId() == null)
                    || (target.storeDisplayName() != null
                        && (target.storeDisplayName().isBlank() || target.storeDisplayName().length() > 120))) {
                throw new IllegalArgumentException();
            }
        }
        JsonNode tree = mapper.valueToTree(input);
        byte[] bytes;
        try { bytes = mapper.writeValueAsBytes(tree); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
        if (bytes.length > 32 * 1024) throw new IllegalArgumentException();
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        NotificationSourceEvent source = new NotificationSourceEvent(
                input.eventId(), input.eventType(), input.eventVersion(), input.occurredAt(),
                input.producer(), input.aggregateType(), input.aggregateId(), input.aggregateId(),
                required(input.correlationId()), input.causationId(), tree, tree);
        return new Validation(input, source, hash);
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private NotificationConsumeResult result(NotificationConsumeResult.Outcome outcome, String code) {
        return new NotificationConsumeResult(outcome, code);
    }

    private record Validation(CommerceNotificationEvent input, NotificationSourceEvent source, String hash) {}
}
