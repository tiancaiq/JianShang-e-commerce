package com.msb.ecom.payment_service.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.model.FakePaymentWebhookEvent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class FakePaymentWebhookEventParser {

    public static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final Pattern EVENT_ID = Pattern.compile("fake_evt_[a-z0-9_-]{8,96}");
    private static final Pattern PROVIDER_REFERENCE =
            Pattern.compile("fake_pi_[0-7][0-9a-hjkmnp-tv-z]{25}");
    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z0-9_]{1,64}");

    private final ObjectMapper objectMapper;

    public FakePaymentWebhookEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Parses only the bounded fake-provider fields required for terminal confirmation.
    public FakePaymentWebhookEvent parse(byte[] rawBody) {
        requireBounded(rawBody);
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException exception) {
            throw invalidPayload();
        }
        requireExactFields(root, Set.of("eventId", "type", "occurredAt", "data"));
        String eventId = requiredText(root, "eventId");
        if (!EVENT_ID.matcher(eventId).matches()) {
            throw invalidPayload();
        }
        FakePaymentWebhookEvent.Type type;
        try {
            type = FakePaymentWebhookEvent.Type.fromWireName(requiredText(root, "type"));
        } catch (IllegalArgumentException exception) {
            throw invalidPayload();
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(requiredText(root, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw invalidPayload();
        }
        JsonNode data = root.get("data");
        Set<String> expectedDataFields = type == FakePaymentWebhookEvent.Type.FAILED
                ? Set.of("providerReference", "failureCode")
                : Set.of("providerReference");
        requireExactFields(data, expectedDataFields);
        String providerReference = requiredText(data, "providerReference");
        if (!PROVIDER_REFERENCE.matcher(providerReference).matches()) {
            throw invalidPayload();
        }
        String failureCode = type == FakePaymentWebhookEvent.Type.FAILED
                ? requiredText(data, "failureCode")
                : null;
        if (failureCode != null && !FAILURE_CODE.matcher(failureCode).matches()) {
            throw invalidPayload();
        }
        return new FakePaymentWebhookEvent(
                eventId, type, occurredAt, providerReference, failureCode);
    }

    // Rejects empty or oversized bodies before signature work or JSON allocation.
    public void requireBounded(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_PAYLOAD_BYTES) {
            throw invalidPayload();
        }
    }

    private void requireExactFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalidPayload();
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalidPayload();
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidPayload();
        }
        return value.textValue();
    }

    private PaymentIntentException invalidPayload() {
        return new PaymentIntentException(
                HttpStatus.BAD_REQUEST,
                "PAYMENT_WEBHOOK_PAYLOAD_INVALID",
                "Payment webhook payload is invalid.");
    }
}
