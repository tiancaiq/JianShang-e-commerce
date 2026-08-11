package com.msb.ecom.payment_service.webhook;

import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripePaymentWebhookAdapterTests {

    private static final String SECRET = "whsec_adapter_test";

    @Test
    void verifiesExactPayloadAndMapsSucceededPaymentIntent() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        byte[] body = payload("payment_intent.succeeded", "succeeded");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Stripe-Signature", signature(timestamp, body));

        VerifiedPaymentProviderEvent event = new StripePaymentWebhookAdapter(SECRET)
                .verifyAndParse(body, headers, Instant.now());

        assertThat(event.provider()).isEqualTo("STRIPE_TEST_V1");
        assertThat(event.eventId()).isEqualTo("evt_test_approved");
        assertThat(event.providerObjectReference()).isEqualTo("pi_test_approved");
        assertThat(event.kind()).isEqualTo(VerifiedPaymentProviderEvent.Kind.PAYMENT_SUCCEEDED);
    }

    @Test
    void rejectsAnyPayloadMutationAfterSigning() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        byte[] body = payload("payment_intent.succeeded", "succeeded");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Stripe-Signature", signature(timestamp, body));
        byte[] changed = payload("payment_intent.processing", "processing");

        assertThatThrownBy(() -> new StripePaymentWebhookAdapter(SECRET)
                .verifyAndParse(changed, headers, Instant.now()))
                .isInstanceOf(PaymentIntentException.class)
                .hasMessageContaining("could not be verified");
    }

    private byte[] payload(String type, String status) {
        return ("{\"id\":\"evt_test_approved\",\"object\":\"event\","
                + "\"api_version\":\"2026-02-25.clover\",\"created\":1700000000,"
                + "\"type\":\"" + type + "\",\"data\":{\"object\":{"
                + "\"id\":\"pi_test_approved\",\"object\":\"payment_intent\","
                + "\"status\":\"" + status + "\"}}}").getBytes(StandardCharsets.UTF_8);
    }

    private String signature(long timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
