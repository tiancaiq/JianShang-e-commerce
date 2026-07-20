package com.msb.ecom.payment_service.provider;

public interface PaymentProvider {

    String providerName();

    PaymentProviderResult createIntent(PaymentProviderCommand command);

    String actionReference(String paymentIntentId);
}
