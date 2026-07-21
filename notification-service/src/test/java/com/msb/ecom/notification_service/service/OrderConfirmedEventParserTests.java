package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.model.NotificationPoisonException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedEventParserTests {

    private final OrderConfirmedEventParser parser = new OrderConfirmedEventParser();

    @Test
    void parsesStrictV2AndHashesEquivalentJsonCanonically() {
        var event = parser.parseEnvelope(eventJson(2, id(900), id(2), true));
        var payload = parser.parseSupportedPayload(event);

        assertThat(event.isSupportedOrderConfirmed()).isTrue();
        assertThat(payload.orderId()).isEqualTo(id(1));
        assertThat(payload.recipientUserId()).isEqualTo(id(2));
        assertThat(parser.canonicalHash(event)).hasSize(64);

        String reordered = eventJson(2, id(900), id(2), true)
                .replace(
                        "\"eventId\":\"" + id(900) + "\",\"eventType\":\"order.confirmed\"",
                        "\"eventType\":\"order.confirmed\",\"eventId\":\"" + id(900) + "\"");
        assertThat(parser.canonicalHash(parser.parseEnvelope(reordered)))
                .isEqualTo(parser.canonicalHash(event));
    }

    @Test
    void acceptsV1EnvelopeForDurableUnsupportedClassification() {
        var event = parser.parseEnvelope(eventJson(1, id(901), id(2), false));

        assertThat(event.isSupportedOrderConfirmed()).isFalse();
        assertThat(parser.canonicalHash(event)).hasSize(64);
    }

    @Test
    void rejectsOversizeUnknownFieldsIdentityMismatchAndSensitivePayloadFields() {
        assertThatThrownBy(() -> parser.parseEnvelope("x".repeat(20_000)))
                .isInstanceOf(NotificationPoisonException.class);
        assertThatThrownBy(() -> parser.parseEnvelope(
                eventJson(2, id(902), id(2), true)
                        .replace("\"payload\":", "\"unexpected\":true,\"payload\":")))
                .isInstanceOf(NotificationPoisonException.class);

        assertThatThrownBy(() -> parser.parseEnvelope(eventJson(2, id(903), id(2), true)
                .replace("\"partitionKey\":\"" + id(1) + "\"",
                        "\"partitionKey\":\"" + id(99) + "\"")))
                .isInstanceOf(NotificationPoisonException.class);

        var sensitive = parser.parseEnvelope(eventJson(2, id(904), id(2), true)
                .replace("\"recipientUserId\":\"" + id(2) + "\"",
                        "\"recipientUserId\":\"" + id(2) + "\",\"email\":\"buyer@example.test\""));
        assertThatThrownBy(() -> parser.parseSupportedPayload(sensitive))
                .isInstanceOf(NotificationPoisonException.class);
    }

    static String eventJson(int version, String eventId, String recipientId, boolean includeRecipient) {
        String recipient = includeRecipient
                ? ",\"recipientUserId\":\"" + recipientId + "\""
                : "";
        return """
                {
                  "eventId":"%s","eventType":"order.confirmed","eventVersion":%d,
                  "occurredAt":"2026-07-20T01:00:00Z","producer":"order-service",
                  "aggregateType":"ORDER","aggregateId":"%s","partitionKey":"%s",
                  "correlationId":"correlation-notification-1","causationId":"%s",
                  "payload":{
                    "orderId":"%s","checkoutId":"%s","paymentIntentId":"%s",
                    "status":"CONFIRMED","businessIds":["%s","%s"],
                    "confirmedAt":"2026-07-20T01:00:00Z"%s
                  }
                }
                """.formatted(
                eventId,
                version,
                id(1),
                id(1),
                id(800),
                id(1),
                id(10),
                id(20),
                id(100),
                id(101),
                recipient);
    }

    static String id(int suffix) {
        return "01K" + String.format("%023d", suffix);
    }
}
