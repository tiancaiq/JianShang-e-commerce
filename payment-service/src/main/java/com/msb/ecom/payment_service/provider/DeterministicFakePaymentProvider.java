package com.msb.ecom.payment_service.provider;

import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import org.springframework.stereotype.Component;

@Component
public class DeterministicFakePaymentProvider implements PaymentProvider {

    public static final String PROVIDER = "FAKE_LOCAL_DEMO_V1";

    @Override
    public String providerName() {
        return PROVIDER;
    }

    // Produces stable local references and never performs or reports external money movement.
    @Override
    public PaymentProviderResult createIntent(PaymentProviderCommand command) {
        return new PaymentProviderResult(
                PaymentIntentStatus.REQUIRES_ACTION,
                "fake_pi_" + command.paymentIntentId().toLowerCase(),
                "FAKE_HOSTED_ACTION",
                null,
                null);
    }

    @Override
    public String actionReference(String paymentIntentId) {
        return "fake_action_" + paymentIntentId.toLowerCase();
    }
}
