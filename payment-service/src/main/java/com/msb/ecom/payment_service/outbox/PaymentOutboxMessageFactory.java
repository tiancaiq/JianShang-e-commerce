package com.msb.ecom.payment_service.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class PaymentOutboxMessageFactory {

    private static final int MAX_STORED_PAYLOAD_CHARS = 4 * 1024;
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern CORRELATION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern PROVIDER_EVENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Set<String> PAYLOAD_FIELDS =
            Set.of("paymentIntentId", "checkoutId", "status", "amount", "currency", "providerEventId");

    private final ObjectMapper objectMapper;

    public PaymentOutboxMessageFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // Converts only the version-1 payment outbox schema into a transport-safe event.
    public PaymentEventMessage create(PaymentOutboxRecord record) {
        try {
            require(record.payloadJson() != null
                    && !record.payloadJson().isBlank()
                    && record.payloadJson().length() <= MAX_STORED_PAYLOAD_CHARS);
            JsonNode payload = objectMapper.readTree(record.payloadJson());
            require(payload != null && payload.isObject());
            java.util.Set<String> fields = new java.util.HashSet<>();
            payload.fieldNames().forEachRemaining(fields::add);
            require(fields.equals(PAYLOAD_FIELDS));

            String paymentIntentId = text(payload, "paymentIntentId");
            String checkoutId = text(payload, "checkoutId");
            String status = text(payload, "status");
            String currency = text(payload, "currency");
            String providerEventId = text(payload, "providerEventId");
            String amountText = text(payload, "amount");
            require(amountText.length() <= 32);
            BigDecimal amount = new BigDecimal(amountText);

            require("payment".equals(record.aggregateType()));
            require("payment-service".equals(record.producer()));
            require(record.eventVersion() == 1);
            require(Set.of("payment.succeeded", "payment.failed").contains(record.eventType()));
            require(record.eventType().equals("payment." + status.toLowerCase()));
            require(ULID.matcher(record.id()).matches());
            require(ULID.matcher(record.paymentIntentId()).matches());
            require(record.paymentIntentId().equals(paymentIntentId));
            require(ULID.matcher(checkoutId).matches());
            require(CORRELATION.matcher(record.correlationId()).matches());
            require(PROVIDER_EVENT.matcher(providerEventId).matches());
            require(providerEventId.equals(record.causationId()));
            require("USD".equals(currency));
            require(validAmount(amount));
            require(record.occurredAt() != null);

            return new PaymentEventMessage(
                    record.id(),
                    record.eventType(),
                    record.eventVersion(),
                    record.occurredAt(),
                    record.correlationId(),
                    paymentIntentId,
                    checkoutId,
                    paymentIntentId,
                    new PaymentEventMessage.Payload(status, amount, currency, providerEventId));
        } catch (Exception exception) {
            throw new PaymentEventTransportException("OUTBOX_EVENT_INVALID");
        }
    }

    private boolean validAmount(BigDecimal amount) {
        return amount.compareTo(BigDecimal.ZERO) > 0
                && amount.scale() >= 0
                && amount.scale() <= 4
                && amount.precision() <= 19;
    }

    private String text(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        require(value != null && value.isTextual() && !value.textValue().isBlank());
        return value.textValue();
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("invalid payment outbox event");
        }
    }
}
