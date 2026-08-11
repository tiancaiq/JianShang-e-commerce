package com.msb.ecom.payment_service.provider;

import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = DeterministicFakePaymentProvider.PROVIDER,
        matchIfMissing = true)
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

    @Override
    public PaymentProviderResult retrieveIntent(String providerReference) {
        return new PaymentProviderResult(
                PaymentIntentStatus.REQUIRES_ACTION,
                providerReference,
                "FAKE_HOSTED_ACTION",
                null,
                null);
    }

    // Produces one stable fake reference and never moves real money.
    @Override
    public PaymentRefundResult refund(PaymentRefundCommand command) {
        return new PaymentRefundResult(
                "SUCCEEDED",
                "fake_refund_" + command.cancellationRequestId().toLowerCase());
    }

    @Override
    public PaymentRefundResult retrieveRefund(String providerReference) {
        return new PaymentRefundResult("SUCCEEDED", providerReference);
    }
}
