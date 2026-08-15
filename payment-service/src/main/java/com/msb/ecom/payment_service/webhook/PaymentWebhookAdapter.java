package com.msb.ecom.payment_service.webhook;

import org.springframework.http.HttpHeaders;

import java.time.Instant;

public interface PaymentWebhookAdapter {

    String providerName();

    VerifiedPaymentProviderEvent verifyAndParse(
            byte[] rawBody, HttpHeaders headers, Instant receivedAt);
}
