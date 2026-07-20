package com.msb.ecom.payment_service.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.model.FakePaymentWebhookEvent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentWebhookEventParserTests {

    private final FakePaymentWebhookEventParser parser =
            new FakePaymentWebhookEventParser(new ObjectMapper());

    @Test
    void parsesOnlyTheApprovedSuccessAndFailureSchemas() {
        FakePaymentWebhookEvent succeeded = parser.parse(json("""
                {
                  "eventId": "fake_evt_parser_0001",
                  "type": "payment_intent.succeeded",
                  "occurredAt": "2026-07-20T01:00:00Z",
                  "data": {
                    "providerReference": "fake_pi_01k00000000000000000000001"
                  }
                }
                """));
        FakePaymentWebhookEvent failed = parser.parse(json("""
                {
                  "eventId": "fake_evt_parser_0002",
                  "type": "payment_intent.failed",
                  "occurredAt": "2026-07-20T01:00:00Z",
                  "data": {
                    "providerReference": "fake_pi_01k00000000000000000000001",
                    "failureCode": "CARD_DECLINED"
                  }
                }
                """));

        assertThat(succeeded.type()).isEqualTo(FakePaymentWebhookEvent.Type.SUCCEEDED);
        assertThat(succeeded.failureCode()).isNull();
        assertThat(failed.type()).isEqualTo(FakePaymentWebhookEvent.Type.FAILED);
        assertThat(failed.failureCode()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void rejectsUnknownMissingAndTypeSpecificFields() {
        assertInvalid("""
                {
                  "eventId": "fake_evt_parser_0003",
                  "type": "payment_intent.succeeded",
                  "occurredAt": "2026-07-20T01:00:00Z",
                  "unexpected": "value",
                  "data": {"providerReference": "fake_pi_01k00000000000000000000001"}
                }
                """);
        assertInvalid("""
                {
                  "eventId": "fake_evt_parser_0004",
                  "type": "payment_intent.failed",
                  "occurredAt": "2026-07-20T01:00:00Z",
                  "data": {"providerReference": "fake_pi_01k00000000000000000000001"}
                }
                """);
        assertInvalid("""
                {
                  "eventId": "fake_evt_parser_0005",
                  "type": "payment_intent.succeeded",
                  "occurredAt": "2026-07-20T01:00:00Z",
                  "data": {
                    "providerReference": "fake_pi_01k00000000000000000000001",
                    "failureCode": "CARD_DECLINED"
                  }
                }
                """);
        assertInvalid("""
                {
                  "eventId": "fake_evt_parser_0006",
                  "type": "payment_intent.refunded",
                  "occurredAt": "2026-07-20T01:00:00Z",
                  "data": {"providerReference": "fake_pi_01k00000000000000000000001"}
                }
                """);
    }

    @Test
    void rejectsMalformedAndOversizedBodiesWithSafeError() {
        assertInvalid("{not-json");
        byte[] oversized = new byte[FakePaymentWebhookEventParser.MAX_PAYLOAD_BYTES + 1];

        assertThatThrownBy(() -> parser.parse(oversized))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("PAYMENT_WEBHOOK_PAYLOAD_INVALID");
                    assertThat(exception.getMessage()).doesNotContain(new String(oversized));
                });
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> parser.parse(json(value)))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("PAYMENT_WEBHOOK_PAYLOAD_INVALID");
                });
    }

    private byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
