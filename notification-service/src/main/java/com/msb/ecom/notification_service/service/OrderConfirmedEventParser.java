package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.msb.ecom.notification_service.model.NotificationPoisonException;
import com.msb.ecom.notification_service.model.NotificationSourceEvent;
import com.msb.ecom.notification_service.model.OrderConfirmedNotification;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class OrderConfirmedEventParser {

    static final int MAX_EVENT_BYTES = 16_384;

    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9._-]{0,99}");
    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "eventId",
            "eventType",
            "eventVersion",
            "occurredAt",
            "producer",
            "aggregateType",
            "aggregateId",
            "partitionKey",
            "correlationId",
            "causationId",
            "payload");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "orderId",
            "checkoutId",
            "paymentIntentId",
            "status",
            "businessIds",
            "confirmedAt",
            "recipientUserId");

    private final ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(8)
                            .maxStringLength(4096)
                            .maxNumberLength(20)
                            .build())
                    .build())
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    // Parses only the bounded transport-neutral envelope shared by direct and future adapters.
    public NotificationSourceEvent parseEnvelope(String rawEvent) {
        if (rawEvent == null
                || rawEvent.isBlank()
                || rawEvent.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            throw new NotificationPoisonException();
        }
        try {
            JsonNode root = mapper.readTree(rawEvent);
            requireExactObject(root, ENVELOPE_FIELDS);
            String eventId = requiredText(root, "eventId");
            String eventType = requiredText(root, "eventType");
            int eventVersion = requiredInt(root, "eventVersion");
            Instant occurredAt = requiredInstant(root, "occurredAt");
            String producer = requiredText(root, "producer");
            String aggregateType = requiredText(root, "aggregateType");
            String aggregateId = requiredText(root, "aggregateId");
            String partitionKey = requiredText(root, "partitionKey");
            String correlationId = requiredText(root, "correlationId");
            String causationId = requiredText(root, "causationId");
            JsonNode payload = root.path("payload");

            if (!validId(eventId)
                    || !EVENT_TYPE.matcher(eventType).matches()
                    || eventVersion < 1
                    || eventVersion > 1000
                    || !"order-service".equals(producer)
                    || !"ORDER".equals(aggregateType)
                    || !validId(aggregateId)
                    || !aggregateId.equals(partitionKey)
                    || !CORRELATION.matcher(correlationId).matches()
                    || !validId(causationId)
                    || !payload.isObject()) {
                throw new NotificationPoisonException();
            }
            return new NotificationSourceEvent(
                    eventId,
                    eventType,
                    eventVersion,
                    occurredAt,
                    producer,
                    aggregateType,
                    aggregateId,
                    partitionKey,
                    correlationId,
                    causationId,
                    payload,
                    root);
        } catch (NotificationPoisonException exception) {
            throw exception;
        } catch (JsonProcessingException | DateTimeParseException exception) {
            throw new NotificationPoisonException();
        }
    }

    // Validates the exact recipient-bearing order.confirmed v2 payload.
    public OrderConfirmedNotification parseSupportedPayload(NotificationSourceEvent event) {
        try {
            JsonNode payload = event.payload();
            requireExactObject(payload, PAYLOAD_FIELDS);
            String orderId = requiredText(payload, "orderId");
            String checkoutId = requiredText(payload, "checkoutId");
            String paymentIntentId = requiredText(payload, "paymentIntentId");
            String status = requiredText(payload, "status");
            Instant confirmedAt = requiredInstant(payload, "confirmedAt");
            String recipientUserId = requiredText(payload, "recipientUserId");
            JsonNode businessIdsNode = payload.path("businessIds");
            if (!businessIdsNode.isArray()
                    || businessIdsNode.isEmpty()
                    || businessIdsNode.size() > 50) {
                throw new NotificationPoisonException();
            }
            List<String> businessIds = new ArrayList<>(businessIdsNode.size());
            businessIdsNode.forEach(node -> {
                if (!node.isTextual() || !validId(node.textValue())) {
                    throw new NotificationPoisonException();
                }
                businessIds.add(node.textValue());
            });
            List<String> sorted = businessIds.stream().sorted().toList();
            if (!validId(orderId)
                    || !validId(checkoutId)
                    || !validId(paymentIntentId)
                    || !validId(recipientUserId)
                    || !"CONFIRMED".equals(status)
                    || !orderId.equals(event.aggregateId())
                    || !confirmedAt.equals(event.occurredAt())
                    || !businessIds.equals(sorted)
                    || new HashSet<>(businessIds).size() != businessIds.size()) {
                throw new NotificationPoisonException();
            }
            return new OrderConfirmedNotification(
                    orderId,
                    checkoutId,
                    paymentIntentId,
                    status,
                    List.copyOf(businessIds),
                    confirmedAt,
                    recipientUserId);
        } catch (NotificationPoisonException exception) {
            throw exception;
        } catch (DateTimeParseException exception) {
            throw new NotificationPoisonException();
        }
    }

    // Hashes every normalized envelope and payload field for durable event-ID conflict checks.
    public String canonicalHash(NotificationSourceEvent event) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(event.canonicalTree(), canonical);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void requireExactObject(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw new NotificationPoisonException();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new NotificationPoisonException();
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new NotificationPoisonException();
        }
        return value.textValue();
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new NotificationPoisonException();
        }
        return value.intValue();
    }

    private Instant requiredInstant(JsonNode node, String field) {
        return Instant.parse(requiredText(node, field));
    }

    private boolean validId(String value) {
        return value != null && ULID.matcher(value).matches();
    }

    private void appendCanonical(JsonNode node, StringBuilder value) {
        if (node.isObject()) {
            value.append('{');
            Iterator<String> names = node.fieldNames();
            List<String> sorted = new ArrayList<>();
            names.forEachRemaining(sorted::add);
            sorted.sort(Comparator.naturalOrder());
            for (String name : sorted) {
                appendJsonString(name, value);
                value.append(':');
                appendCanonical(node.get(name), value);
                value.append(',');
            }
            value.append('}');
        } else if (node.isArray()) {
            value.append('[');
            node.forEach(item -> {
                appendCanonical(item, value);
                value.append(',');
            });
            value.append(']');
        } else {
            value.append(node.toString());
        }
    }

    private void appendJsonString(String text, StringBuilder value) {
        try {
            value.append(mapper.writeValueAsString(text));
        } catch (JsonProcessingException exception) {
            throw new NotificationPoisonException();
        }
    }
}
