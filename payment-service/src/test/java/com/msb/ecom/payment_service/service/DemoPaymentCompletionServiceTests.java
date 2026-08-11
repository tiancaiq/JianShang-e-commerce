package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.dto.CompleteDemoPaymentRequest;
import com.msb.ecom.payment_service.dto.PaymentWebhookResponse;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import com.msb.ecom.payment_service.webhook.HmacWebhookVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoPaymentCompletionServiceTests {

    private static final String SECRET = "local-demo-webhook-secret";
    private static final String PAYMENT_ID = "01P00000000000000000000001";
    private static final String BUYER_ID = "01U00000000000000000000001";

    @Test
    void signsDeterministicSuccessAndTraversesWebhookBoundary() {
        PaymentIntentRepository repository = mock(PaymentIntentRepository.class);
        InternalPaymentAuthenticator authenticator = mock(InternalPaymentAuthenticator.class);
        PaymentWebhookService webhooks = mock(PaymentWebhookService.class);
        when(repository.findOwned(PAYMENT_ID, BUYER_ID)).thenReturn(Optional.of(intent(
                PaymentIntentStatus.REQUIRES_ACTION,
                Instant.now().plusSeconds(600))));
        when(webhooks.process(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(new PaymentWebhookResponse(
                        "fake_evt_complete_" + PAYMENT_ID.toLowerCase(),
                        PAYMENT_ID,
                        "SUCCEEDED",
                        "APPLIED",
                        false));

        DemoPaymentCompletionService service = new DemoPaymentCompletionService(
                repository, authenticator, webhooks, new ObjectMapper().findAndRegisterModules(), SECRET, true);
        PaymentWebhookResponse response = service.complete(
                "internal-token",
                PAYMENT_ID,
                new CompleteDemoPaymentRequest(BUYER_ID, "fake_action_" + PAYMENT_ID.toLowerCase()),
                "correlation-demo");

        ArgumentCaptor<String> signature = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(webhooks).process(signature.capture(), body.capture(), org.mockito.ArgumentMatchers.eq("correlation-demo"));
        new HmacWebhookVerifier(SECRET, Duration.ofMinutes(5))
                .verify(signature.getValue(), body.getValue(), Instant.now());
        assertThat(new String(body.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("fake_evt_complete_" + PAYMENT_ID.toLowerCase())
                .contains("payment_intent.succeeded");
        assertThat(response.status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsCompletionAfterCheckoutExpiryBeforeWebhookProcessing() {
        PaymentIntentRepository repository = mock(PaymentIntentRepository.class);
        InternalPaymentAuthenticator authenticator = mock(InternalPaymentAuthenticator.class);
        PaymentWebhookService webhooks = mock(PaymentWebhookService.class);
        when(repository.findOwned(PAYMENT_ID, BUYER_ID)).thenReturn(Optional.of(intent(
                PaymentIntentStatus.REQUIRES_ACTION,
                Instant.now().minusSeconds(1))));
        DemoPaymentCompletionService service = new DemoPaymentCompletionService(
                repository, authenticator, webhooks, new ObjectMapper().findAndRegisterModules(), SECRET, true);

        assertThatThrownBy(() -> service.complete(
                "internal-token",
                PAYMENT_ID,
                new CompleteDemoPaymentRequest(BUYER_ID, "fake_action_" + PAYMENT_ID.toLowerCase()),
                "correlation-demo"))
                .isInstanceOf(PaymentIntentException.class)
                .extracting(exception -> ((PaymentIntentException) exception).code())
                .isEqualTo("PAYMENT_INTENT_EXPIRED");
    }

    private PaymentIntent intent(PaymentIntentStatus status, Instant expiresAt) {
        Instant createdAt = Instant.parse("2026-08-03T00:00:00Z");
        return new PaymentIntent(
                PAYMENT_ID,
                "01C00000000000000000000001",
                2,
                "a".repeat(64),
                BUYER_ID,
                "ORDER_SERVICE",
                List.of("01B00000000000000000000001"),
                new BigDecimal("25.0000"),
                "USD",
                "CARD",
                "AUTOMATIC",
                "PLATFORM",
                "SEPARATE_CHARGE_TRANSFER",
                "FAKE_LOCAL_DEMO_V1",
                "fake_pi_" + PAYMENT_ID.toLowerCase(),
                "FAKE_HOSTED_ACTION",
                status,
                1,
                expiresAt,
                null,
                null,
                createdAt,
                createdAt);
    }
}
