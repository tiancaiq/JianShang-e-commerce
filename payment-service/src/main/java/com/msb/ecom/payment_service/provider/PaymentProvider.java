package com.msb.ecom.payment_service.provider;

public interface PaymentProvider {

    String providerName();

    PaymentProviderResult createIntent(PaymentProviderCommand command);

    String actionReference(String paymentIntentId);

    default PaymentRefundResult refund(PaymentRefundCommand command) {
        throw new UnsupportedOperationException("Refunds are not supported by this provider.");
    }
}
