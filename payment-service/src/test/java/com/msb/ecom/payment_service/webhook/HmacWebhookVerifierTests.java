package com.msb.ecom.payment_service.webhook;

import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacWebhookVerifierTests {

    private static final String SECRET = "unit-test-webhook-secret";
    private final HmacWebhookVerifier verifier =
            new HmacWebhookVerifier(SECRET, Duration.ofMinutes(5));

    @Test
    void acceptsExactFreshSignature() {
        byte[] body = "{\"eventId\":\"fake_evt_unit_0001\"}".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-07-20T01:00:00Z");

        assertThat(verifier.verify(signature(now, body), body, now))
                .isEqualTo(now);
    }

    @Test
    void rejectsModifiedPayloadAndNonCanonicalSignatureFormats() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-07-20T01:00:00Z");

        assertInvalid(signature(now, body), "{ }".getBytes(StandardCharsets.UTF_8),
                "PAYMENT_WEBHOOK_SIGNATURE_INVALID");
        assertInvalid(signature(now, body).toUpperCase(), body,
                "PAYMENT_WEBHOOK_SIGNATURE_INVALID");
        assertInvalid(signature(now, body) + ",v1=" + "0".repeat(64), body,
                "PAYMENT_WEBHOOK_SIGNATURE_INVALID");
        assertInvalid(" t=" + now.getEpochSecond() + ",v1=" + "0".repeat(64), body,
                "PAYMENT_WEBHOOK_SIGNATURE_INVALID");
    }

    @Test
    void rejectsPastAndFutureTimestampsOutsideTolerance() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-07-20T01:00:00Z");

        assertInvalid(signature(now.minusSeconds(301), body), body,
                "PAYMENT_WEBHOOK_TIMESTAMP_INVALID", now);
        assertInvalid(signature(now.plusSeconds(301), body), body,
                "PAYMENT_WEBHOOK_TIMESTAMP_INVALID", now);
    }

    @Test
    void emptySecretFailsClosedWithoutEchoingPayloadOrCredential() {
        HmacWebhookVerifier unconfigured = new HmacWebhookVerifier("", Duration.ofMinutes(5));
        byte[] body = "sensitive-provider-body".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> unconfigured.verify(
                "t=1752973200,v1=" + "0".repeat(64),
                body,
                Instant.ofEpochSecond(1752973200)))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("PAYMENT_WEBHOOK_NOT_CONFIGURED");
                    assertThat(exception.getMessage()).doesNotContain("sensitive-provider-body");
                });
    }

    private void assertInvalid(String signature, byte[] body, String code) {
        assertInvalid(signature, body, code, Instant.parse("2026-07-20T01:00:00Z"));
    }

    private void assertInvalid(String signature, byte[] body, String code, Instant now) {
        assertThatThrownBy(() -> verifier.verify(signature, body, now))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo(code);
                });
    }

    private String signature(Instant timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(Long.toString(timestamp.getEpochSecond()).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return "t=" + timestamp.getEpochSecond()
                    + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
