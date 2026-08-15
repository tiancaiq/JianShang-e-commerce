package com.msb.ecom.payment_service.provider;

import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentConfirmParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "STRIPE_SANDBOX_E2E", matches = "true")
class StripeSandboxIntegrationTests {

    private static final String RUN_TOKEN = runToken();

    @Test
    void createsRetrievesAndIdempotentlyReplaysOneTestModePaymentIntent() {
        StripeTestPaymentProvider provider = provider();
        PaymentProviderCommand command = command(
                id("idempotent-payment"), id("idempotent-checkout"), "0.5000");

        PaymentProviderResult first = provider.createIntent(command);
        PaymentProviderResult replay = provider.createIntent(command);
        PaymentProviderResult retrieved = provider.retrieveIntent(first.providerReference());

        assertThat(first.providerReference()).startsWith("pi_");
        assertThat(replay.providerReference()).isEqualTo(first.providerReference());
        assertThat(retrieved.providerReference()).isEqualTo(first.providerReference());
        assertThat(first.status()).isEqualTo(PaymentIntentStatus.REQUIRES_ACTION);
        assertThat(provider.actionReference(command.paymentIntentId(), first.providerReference()))
                .startsWith(first.providerReference() + "_secret_");
    }

    @Test
    void confirmsCardAndIdempotentlyCreatesAuthoritativeFullRefund() throws Exception {
        StripeTestPaymentProvider provider = provider();
        PaymentProviderCommand command = command(
                id("success-payment"), id("success-checkout"), "0.5000");
        PaymentProviderResult created = provider.createIntent(command);
        PaymentIntent confirmed = client().v1().paymentIntents().confirm(
                created.providerReference(), PaymentIntentConfirmParams.builder()
                        .setPaymentMethod("pm_card_visa")
                        .build());

        assertThat(confirmed.getAmount()).isEqualTo(50L);
        assertThat(confirmed.getCurrency()).isEqualTo("usd");
        assertThat(confirmed.getStatus()).isEqualTo("succeeded");

        PaymentRefundCommand refund = new PaymentRefundCommand(
                id("full-refund"), command.paymentIntentId(), confirmed.getId(),
                id("full-order"), id("full-cancellation"),
                new BigDecimal("0.5000"), "USD");
        PaymentRefundResult first = provider.refund(refund);
        PaymentRefundResult replay = provider.refund(refund);

        assertThat(replay.providerReference()).isEqualTo(first.providerReference());
        assertThat(provider.retrieveRefund(first.providerReference()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void createsOnlyTheAuthoritativeBusinessGroupPartialRefund() throws Exception {
        StripeTestPaymentProvider provider = provider();
        PaymentProviderCommand command = command(
                id("partial-payment"), id("partial-checkout"), "8.5000");
        PaymentProviderResult created = provider.createIntent(command);
        PaymentIntent confirmed = client().v1().paymentIntents().confirm(
                created.providerReference(), PaymentIntentConfirmParams.builder()
                        .setPaymentMethod("pm_card_visa")
                        .build());
        PaymentRefundCommand refund = new PaymentRefundCommand(
                id("partial-refund"), command.paymentIntentId(), confirmed.getId(),
                id("partial-order"), id("partial-return"),
                new BigDecimal("7.5000"), "USD");

        PaymentRefundResult result = provider.refund(refund);

        assertThat(result.providerReference()).startsWith("re_");
        assertThat(client().v1().refunds().retrieve(result.providerReference()).getAmount())
                .isEqualTo(750L);
    }

    @Test
    void declinedCardNeverMapsToInternalSuccess() {
        StripeTestPaymentProvider provider = provider();
        PaymentProviderResult created = provider.createIntent(command(
                id("declined-payment"), id("declined-checkout"), "0.5000"));

        assertThatThrownBy(() -> client().v1().paymentIntents().confirm(
                created.providerReference(), PaymentIntentConfirmParams.builder()
                        .setPaymentMethod("pm_card_visa_chargeDeclined")
                        .build()))
                .isInstanceOf(CardException.class);
        assertThat(provider.retrieveIntent(created.providerReference()).status())
                .isNotEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    @Test
    void authenticationRequiredCardRemainsNonterminalUntilBrowserAuthentication() throws Exception {
        StripeTestPaymentProvider provider = provider();
        String checkoutId = id("authentication-checkout");
        PaymentProviderResult created = provider.createIntent(command(
                id("authentication-payment"), checkoutId, "0.5000"));

        PaymentIntent intent = client().v1().paymentIntents().confirm(
                created.providerReference(), PaymentIntentConfirmParams.builder()
                        .setPaymentMethod("pm_card_authenticationRequired")
                        .setReturnUrl("http://localhost:4200/checkout/" + checkoutId)
                        .build());

        assertThat(intent.getStatus()).isEqualTo("requires_action");
        assertThat(provider.retrieveIntent(intent.getId()).status())
                .isEqualTo(PaymentIntentStatus.REQUIRES_ACTION);
    }

    private PaymentProviderCommand command(
            String paymentIntentId, String checkoutId, String amount) {
        return new PaymentProviderCommand(
                paymentIntentId, "payment:create:" + paymentIntentId + ":v1", checkoutId,
                new BigDecimal(amount), "USD", "CARD", "AUTOMATIC", "PLATFORM",
                "SEPARATE_CHARGE_TRANSFER", List.of("01KZSTRIPETESTBUSINESS000"));
    }

    private StripeClient client() {
        return new StripeClient(required("PAYMENT_STRIPE_SECRET_KEY"));
    }

    private String id(String purpose) {
        return "stripe-sandbox-" + purpose + "-" + RUN_TOKEN;
    }

    private static String runToken() {
        String configured = System.getenv("STRIPE_SANDBOX_RUN_ID");
        return configured == null || configured.isBlank()
                ? Long.toUnsignedString(System.nanoTime())
                : configured.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private StripeTestPaymentProvider provider() {
        StripeTestPaymentProvider provider = new StripeTestPaymentProvider(
                true,
                true,
                required("PAYMENT_STRIPE_SECRET_KEY"),
                required("PAYMENT_STRIPE_PUBLISHABLE_KEY"),
                required("PAYMENT_STRIPE_WEBHOOK_SECRET"),
                "http://localhost:4200/checkout",
                "2026-02-25.clover");
        provider.validateConfiguration();
        return provider;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }
}
