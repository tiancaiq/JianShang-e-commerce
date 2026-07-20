package com.msb.ecom.payment_service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOutboxMessageFactoryTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PaymentOutboxMessageFactory factory =
            new PaymentOutboxMessageFactory(objectMapper);

    @Test
    void createsTheStableVersionedEnvelopeWithAggregatePartitionKey() {
        PaymentEventMessage message = factory.create(record("""
                {
                  "paymentIntentId":"01K00000000000000000000001",
                  "checkoutId":"01K00000000000000000000002",
                  "status":"SUCCEEDED",
                  "amount":"42.2500",
                  "currency":"USD",
                  "providerEventId":"fake_evt_0001"
                }
                """));

        assertThat(message.eventId()).isEqualTo("01K00000000000000000000003");
        assertThat(message.eventType()).isEqualTo("payment.succeeded");
        assertThat(message.schemaVersion()).isEqualTo(1);
        assertThat(message.occurredAt()).isEqualTo(Instant.parse("2026-07-20T01:00:00Z"));
        assertThat(message.correlationId()).isEqualTo("correlation-payment");
        assertThat(message.paymentIntentId()).isEqualTo("01K00000000000000000000001");
        assertThat(message.checkoutId()).isEqualTo("01K00000000000000000000002");
        assertThat(message.partitionKey()).isEqualTo(message.paymentIntentId());
        assertThat(message.payload().amount()).isEqualByComparingTo("42.2500");
        assertThat(message.payload().providerEventId()).isEqualTo("fake_evt_0001");

        var envelope = objectMapper.valueToTree(message);
        assertThat(toSet(envelope.fieldNames())).containsExactlyInAnyOrder(
                "eventId",
                "eventType",
                "schemaVersion",
                "occurredAt",
                "correlationId",
                "paymentIntentId",
                "checkoutId",
                "partitionKey",
                "payload");
        assertThat(envelope.path("payload").path("amount").isNumber()).isTrue();
    }

    @Test
    void rejectsUnknownFieldsMismatchedTypesAndSensitivePayloadExpansion() {
        assertThatThrownBy(() -> factory.create(record("""
                {
                  "paymentIntentId":"01K00000000000000000000001",
                  "checkoutId":"01K00000000000000000000002",
                  "status":"FAILED",
                  "amount":"42.2500",
                  "currency":"USD",
                  "providerEventId":"fake_evt_0001",
                  "buyerId":"01K00000000000000000000999"
                }
                """)))
                .isInstanceOfSatisfying(PaymentEventTransportException.class,
                        exception -> assertThat(exception.safeCode()).isEqualTo("OUTBOX_EVENT_INVALID"));

        PaymentOutboxRecord mismatched = new PaymentOutboxRecord(
                "01K00000000000000000000003",
                "payment",
                "01K00000000000000000000001",
                "payment.failed",
                1,
                "payment-service",
                record("{}").payloadJson(),
                "correlation-payment",
                "fake_evt_0001",
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:00:01Z"),
                0,
                0);
        assertThatThrownBy(() -> factory.create(mismatched))
                .isInstanceOf(PaymentEventTransportException.class);
    }

    @Test
    void rejectsCausationMismatchOversizedStoragePayloadAndOutOfRangeMoney() {
        PaymentOutboxRecord valid = record("""
                {
                  "paymentIntentId":"01K00000000000000000000001",
                  "checkoutId":"01K00000000000000000000002",
                  "status":"SUCCEEDED",
                  "amount":"42.2500",
                  "currency":"USD",
                  "providerEventId":"fake_evt_0001"
                }
                """);
        assertThatThrownBy(() -> factory.create(copy(
                valid,
                valid.payloadJson(),
                "different_provider_event")))
                .isInstanceOfSatisfying(PaymentEventTransportException.class,
                        exception -> assertThat(exception.safeCode()).isEqualTo("OUTBOX_EVENT_INVALID"));
        assertThatThrownBy(() -> factory.create(copy(
                valid,
                " ".repeat(4 * 1024 + 1),
                valid.causationId())))
                .isInstanceOf(PaymentEventTransportException.class);
        assertThatThrownBy(() -> factory.create(copy(
                valid,
                valid.payloadJson().replace("42.2500", "1234567890123456.0000"),
                valid.causationId())))
                .isInstanceOf(PaymentEventTransportException.class);
    }

    private PaymentOutboxRecord copy(
            PaymentOutboxRecord record,
            String payload,
            String causationId) {
        return new PaymentOutboxRecord(
                record.id(),
                record.aggregateType(),
                record.paymentIntentId(),
                record.eventType(),
                record.eventVersion(),
                record.producer(),
                payload,
                record.correlationId(),
                causationId,
                record.occurredAt(),
                record.createdAt(),
                record.retryCount(),
                record.attemptCount());
    }

    private Set<String> toSet(java.util.Iterator<String> fields) {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        fields.forEachRemaining(result::add);
        return result;
    }

    private PaymentOutboxRecord record(String payload) {
        return new PaymentOutboxRecord(
                "01K00000000000000000000003",
                "payment",
                "01K00000000000000000000001",
                "payment.succeeded",
                1,
                "payment-service",
                payload,
                "correlation-payment",
                "fake_evt_0001",
                Instant.parse("2026-07-20T01:00:00Z"),
                Instant.parse("2026-07-20T01:00:01Z"),
                0,
                0);
    }
}
