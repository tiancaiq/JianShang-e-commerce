package com.msb.ecom.payment_service.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeTestPaymentProviderTests {

    @Test
    void rejectsLiveKeysEvenWhenExternalPaymentsAreEnabled() {
        StripeTestPaymentProvider provider = new StripeTestPaymentProvider(
                true, true, "sk_live_not_allowed", "pk_live_not_allowed", "whsec_test",
                "https://marketplace.example/checkout", "2026-02-25.clover");

        assertThatThrownBy(provider::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-mode secret key");
    }

    @Test
    void requiresBothExplicitExternalAndSandboxGates() {
        StripeTestPaymentProvider provider = new StripeTestPaymentProvider(
                false, true, "sk_test_example", "pk_test_example", "whsec_example",
                "http://localhost:4200/checkout", "2026-02-25.clover");

        assertThatThrownBy(provider::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYMENT_EXTERNAL_PROVIDER_ENABLED=true");
    }

    @Test
    void rejectsApiVersionDriftFromTheSdkPin() {
        StripeTestPaymentProvider provider = new StripeTestPaymentProvider(
                true, true, "sk_test_example", "pk_test_example", "whsec_example",
                "http://localhost:4200/checkout", "unreviewed-version");

        assertThatThrownBy(provider::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SDK pin");
    }
}
