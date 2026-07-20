package com.msb.ecom.payment_service.model;

import com.msb.ecom.payment_service.provider.DeterministicFakePaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentProviderCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIntentDomainTests {

    @Test
    void permitsOnlyStableForwardLifecycleTransitions() {
        assertThat(PaymentIntentStatus.CREATED.canTransitionTo(PaymentIntentStatus.REQUIRES_ACTION)).isTrue();
        assertThat(PaymentIntentStatus.REQUIRES_ACTION.canTransitionTo(PaymentIntentStatus.SUCCEEDED)).isTrue();
        assertThat(PaymentIntentStatus.REQUIRES_ACTION.canTransitionTo(PaymentIntentStatus.PROCESSING)).isTrue();
        assertThat(PaymentIntentStatus.PROCESSING.canTransitionTo(PaymentIntentStatus.SUCCEEDED)).isTrue();
        assertThat(PaymentIntentStatus.SUCCEEDED.canTransitionTo(PaymentIntentStatus.FAILED)).isFalse();
        assertThat(PaymentIntentStatus.FAILED.canTransitionTo(PaymentIntentStatus.CREATED)).isFalse();

        PaymentIntent succeeded = intent(PaymentIntentStatus.SUCCEEDED);
        assertThatThrownBy(() -> succeeded.transition(
                PaymentIntentStatus.FAILED, null, null, null, null, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Payment intent transition is not allowed.");
    }

    @Test
    void fakeProviderIsDeterministicAndNeverReportsCapturedMoney() {
        DeterministicFakePaymentProvider provider = new DeterministicFakePaymentProvider();
        PaymentProviderCommand command = new PaymentProviderCommand(
                "01K00000000000000000000001",
                "pay-key-0001",
                "01K00000000000000000000002",
                new BigDecimal("10.0000"),
                "USD",
                "CARD",
                "AUTOMATIC",
                "PLATFORM",
                "SEPARATE_CHARGE_TRANSFER",
                List.of("01K00000000000000000000003"));

        var first = provider.createIntent(command);
        var second = provider.createIntent(command);

        assertThat(first).isEqualTo(second);
        assertThat(first.status()).isEqualTo(PaymentIntentStatus.REQUIRES_ACTION);
        assertThat(first.providerReference()).isEqualTo("fake_pi_01k00000000000000000000001");
        assertThat(provider.actionReference(command.paymentIntentId()))
                .isEqualTo("fake_action_01k00000000000000000000001");
        assertThat(first.status()).isNotIn(PaymentIntentStatus.PROCESSING, PaymentIntentStatus.SUCCEEDED);
    }

    private PaymentIntent intent(PaymentIntentStatus status) {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        return new PaymentIntent(
                "01K00000000000000000000001",
                "01K00000000000000000000002",
                1,
                "a".repeat(64),
                "01K00000000000000000000003",
                "ORDER_SERVICE",
                List.of("01K00000000000000000000004"),
                new BigDecimal("10.0000"),
                "USD",
                "CARD",
                "AUTOMATIC",
                "PLATFORM",
                "SEPARATE_CHARGE_TRANSFER",
                DeterministicFakePaymentProvider.PROVIDER,
                null,
                null,
                status,
                0,
                now.plusSeconds(900),
                null,
                null,
                now,
                now);
    }
}
