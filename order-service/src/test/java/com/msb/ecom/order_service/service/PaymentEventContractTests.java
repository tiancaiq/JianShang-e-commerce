package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.OrderConfirmationProperties;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import com.msb.ecom.order_service.model.PaymentEventEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEventContractTests {

    private final PaymentEventEnvelopeParser parser =
            new PaymentEventEnvelopeParser(new ObjectMapper().findAndRegisterModules());
    private final PaymentEventValidator validator = new PaymentEventValidator();

    @Test
    void parsesAndValidatesTheExactPaymentSucceededV1Envelope() {
        PaymentEventEnvelope event = parser.parse(envelope("").getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> validator.validate(event)).doesNotThrowAnyException();
        assertThat(event.paymentIntentId()).isEqualTo("01K00000000000000000000800");
        assertThat(event.payload().amount()).isEqualByComparingTo("30.0000");
    }

    @Test
    void rejectsUnknownFieldsOversizePayloadAndIdentityMismatch() {
        assertThatThrownBy(() -> parser.parse(envelope(",\"buyerId\":\"forbidden\"")
                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PAYMENT_EVENT_INVALID"));
        assertThatThrownBy(() -> parser.parse(new byte[16 * 1024 + 1]))
                .isInstanceOf(OrderConfirmationException.class);

        PaymentEventEnvelope event = parser.parse(envelope("").getBytes(StandardCharsets.UTF_8));
        PaymentEventEnvelope mismatch = new PaymentEventEnvelope(
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.occurredAt(),
                event.correlationId(),
                event.paymentIntentId(),
                event.checkoutId(),
                "01K00000000000000000000999",
                event.payload());
        assertThatThrownBy(() -> validator.validate(mismatch))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PAYMENT_EVENT_INVALID"));
    }

    @Test
    void rejectsScalarCoercionAndAmountsOutsideTheDecimalContract() {
        assertThatThrownBy(() -> parser.parse(envelope("")
                .replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")
                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PAYMENT_EVENT_INVALID"));
        assertThatThrownBy(() -> parser.parse(envelope("")
                .replace("\"amount\":30.0000", "\"amount\":\"30.0000\"")
                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PAYMENT_EVENT_INVALID"));

        PaymentEventEnvelope event = parser.parse(envelope("").getBytes(StandardCharsets.UTF_8));
        PaymentEventEnvelope oversizedAmount = new PaymentEventEnvelope(
                event.eventId(),
                event.eventType(),
                event.schemaVersion(),
                event.occurredAt(),
                event.correlationId(),
                event.paymentIntentId(),
                event.checkoutId(),
                event.partitionKey(),
                new PaymentEventEnvelope.Payload(
                        event.payload().status(),
                        new BigDecimal("1234567890123456.0000"),
                        event.payload().currency(),
                        event.payload().providerEventId()));
        assertThatThrownBy(() -> validator.validate(oversizedAmount))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PAYMENT_EVENT_INVALID"));
    }

    @Test
    void defaultConfigurationIsDisabledAndBoundsLeaseAndConsumerName() {
        OrderConfirmationProperties properties = new OrderConfirmationProperties(
                false,
                "order-service-order-confirmation-v1",
                Duration.ofSeconds(30));
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.processingLease()).isEqualTo(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new OrderConfirmationProperties(
                true, "INVALID NAME", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmationProperties(
                true, "order-confirmation-v1", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String envelope(String extra) {
        return """
                {
                  "eventId":"01K00000000000000000000900",
                  "eventType":"payment.succeeded",
                  "schemaVersion":1,
                  "occurredAt":"2026-07-20T01:00:00Z",
                  "correlationId":"correlation-order-confirmation",
                  "paymentIntentId":"01K00000000000000000000800",
                  "checkoutId":"01K00000000000000000000001",
                  "partitionKey":"01K00000000000000000000800",
                  "payload":{
                    "status":"SUCCEEDED",
                    "amount":30.0000,
                    "currency":"USD",
                    "providerEventId":"fake_evt_order_0001"
                  }%s
                }
                """.formatted(extra);
    }
}
