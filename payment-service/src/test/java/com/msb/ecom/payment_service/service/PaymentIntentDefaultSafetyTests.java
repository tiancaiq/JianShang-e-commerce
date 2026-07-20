package com.msb.ecom.payment_service.service;

import com.msb.ecom.payment_service.dto.CreatePaymentIntentRequest;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentProviderCommand;
import com.msb.ecom.payment_service.provider.PaymentProviderResult;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import com.msb.ecom.payment_service.webhook.FakePaymentWebhookEventParser;
import com.msb.ecom.payment_service.webhook.HmacWebhookVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIntentDefaultSafetyTests {

    @Test
    void defaultDisabledBoundaryRejectsBeforeAuthenticationPersistenceOrProviderUse() {
        PaymentIntentRepository repository = new PaymentIntentRepository(null);
        PaymentProvider provider = unusedProvider();
        InternalPaymentAuthenticator authenticator = new InternalPaymentAuthenticator("unused-token");
        PaymentIntentService service = new PaymentIntentService(
                repository,
                provider,
                authenticator,
                new PaymentUlidGenerator(),
                new TransactionTemplate(),
                false);

        assertThatThrownBy(() -> service.create(
                "do-not-log-this-token",
                "payment-key-0001",
                request(),
                "correlation-1"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("PAYMENT_INTENTS_DISABLED");
                    assertThat(exception.getMessage()).doesNotContain("do-not-log-this-token");
                });
    }

    @Test
    void emptyInternalCredentialNeverAuthenticatesEvenWithEmptyInput() {
        InternalPaymentAuthenticator authenticator = new InternalPaymentAuthenticator("");

        assertThatThrownBy(() -> authenticator.requireAuthenticated(""))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("PAYMENT_INTERNAL_AUTH_REQUIRED");
                });
    }

    @Test
    void internalCredentialAcceptsExactTokenAndRejectsDifferentLengthTokens() {
        InternalPaymentAuthenticator authenticator =
                new InternalPaymentAuthenticator("fixed-service-token");

        authenticator.requireAuthenticated("fixed-service-token");
        assertThatThrownBy(() -> authenticator.requireAuthenticated("short"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PAYMENT_INTERNAL_AUTH_REQUIRED"));
        assertThatThrownBy(() -> authenticator.requireAuthenticated(
                "a-much-longer-service-token-than-the-configured-value"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PAYMENT_INTERNAL_AUTH_REQUIRED"));
    }

    @Test
    void defaultDisabledWebhookRejectsBeforeSignatureParsingOrPersistence() {
        PaymentIntentRepository repository = new PaymentIntentRepository(null);
        FakePaymentWebhookEventParser parser = new FakePaymentWebhookEventParser(new ObjectMapper());
        HmacWebhookVerifier verifier = new HmacWebhookVerifier(
                "unused-webhook-secret", Duration.ofMinutes(5));
        PaymentWebhookService service = new PaymentWebhookService(
                repository,
                parser,
                verifier,
                new PaymentUlidGenerator(),
                new TransactionTemplate(),
                new ObjectMapper(),
                false);

        assertThatThrownBy(() -> service.process(
                "secret-signature",
                "secret-payload".getBytes(),
                "correlation-default-off"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("PAYMENT_WEBHOOKS_DISABLED");
                    assertThat(exception.getMessage())
                            .doesNotContain("secret-signature", "secret-payload");
                });
    }

    @Test
    void oversizedWebhookIsRejectedBeforeSignatureVerification() {
        FakePaymentWebhookEventParser parser = new FakePaymentWebhookEventParser(new ObjectMapper());
        HmacWebhookVerifier verifier = new HmacWebhookVerifier(
                "unused-webhook-secret", Duration.ofMinutes(5)) {
            @Override
            public Instant verify(String signatureHeader, byte[] rawBody, Instant now) {
                throw new AssertionError("signature verification must not run for oversized payloads");
            }
        };
        PaymentWebhookService service = new PaymentWebhookService(
                new PaymentIntentRepository(null),
                parser,
                verifier,
                new PaymentUlidGenerator(),
                new TransactionTemplate(),
                new ObjectMapper(),
                true);

        assertThatThrownBy(() -> service.process(
                "not-evaluated",
                new byte[FakePaymentWebhookEventParser.MAX_PAYLOAD_BYTES + 1],
                "correlation-oversized"))
                .isInstanceOfSatisfying(PaymentIntentException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("PAYMENT_WEBHOOK_PAYLOAD_INVALID");
                });
    }

    private PaymentProvider unusedProvider() {
        return new PaymentProvider() {
            @Override
            public String providerName() {
                throw new AssertionError("provider must remain unused while disabled");
            }

            @Override
            public PaymentProviderResult createIntent(PaymentProviderCommand command) {
                throw new AssertionError("provider must remain unused while disabled");
            }

            @Override
            public String actionReference(String paymentIntentId) {
                throw new AssertionError("provider must remain unused while disabled");
            }
        };
    }

    private CreatePaymentIntentRequest request() {
        return new CreatePaymentIntentRequest(
                "01K00000000000000000000001",
                1,
                "a".repeat(64),
                "01K00000000000000000000002",
                List.of("01K00000000000000000000003"),
                new BigDecimal("12.3400"),
                "USD",
                Instant.now().plusSeconds(900));
    }
}
