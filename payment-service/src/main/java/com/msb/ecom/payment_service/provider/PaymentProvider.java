package com.msb.ecom.payment_service.provider;

public interface PaymentProvider {

    String providerName();

    PaymentProviderResult createIntent(PaymentProviderCommand command);

    default PaymentProviderResult retrieveIntent(String providerReference) {
        throw new UnsupportedOperationException("Payment retrieval is not supported by this provider.");
    }

    String actionReference(String paymentIntentId);

    default String actionReference(String paymentIntentId, String providerReference) {
        return actionReference(paymentIntentId);
    }

    default String publicClientKey() {
        return null;
    }

    default String returnUrl(String checkoutId) {
        return null;
    }

    default PaymentRefundResult refund(PaymentRefundCommand command) {
        throw new UnsupportedOperationException("Refunds are not supported by this provider.");
    }

    default PaymentRefundResult retrieveRefund(String providerReference) {
        throw new UnsupportedOperationException("Refund retrieval is not supported by this provider.");
    }
}
