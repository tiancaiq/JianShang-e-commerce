package com.msb.ecom.payment_service.webhook;

import com.msb.ecom.payment_service.model.FakePaymentWebhookEvent;
import com.msb.ecom.payment_service.provider.DeterministicFakePaymentProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = DeterministicFakePaymentProvider.PROVIDER,
        matchIfMissing = true)
public class FakePaymentWebhookAdapter implements PaymentWebhookAdapter {

    private final FakePaymentWebhookEventParser parser;
    private final HmacWebhookVerifier verifier;

    public FakePaymentWebhookAdapter(
            FakePaymentWebhookEventParser parser, HmacWebhookVerifier verifier) {
        this.parser = parser;
        this.verifier = verifier;
    }

    @Override
    public String providerName() {
        return DeterministicFakePaymentProvider.PROVIDER;
    }

    @Override
    public VerifiedPaymentProviderEvent verifyAndParse(
            byte[] rawBody, HttpHeaders headers, Instant receivedAt) {
        parser.requireBounded(rawBody);
        Instant signedAt = verifier.verify(headers.getFirst("X-MSB-Signature"), rawBody, receivedAt);
        FakePaymentWebhookEvent event = parser.parse(rawBody);
        VerifiedPaymentProviderEvent.Kind kind = event.type() == FakePaymentWebhookEvent.Type.SUCCEEDED
                ? VerifiedPaymentProviderEvent.Kind.PAYMENT_SUCCEEDED
                : VerifiedPaymentProviderEvent.Kind.PAYMENT_FAILED;
        return new VerifiedPaymentProviderEvent(
                providerName(), event.eventId(), event.type().wireName(), kind,
                event.providerReference(), null,
                event.type().targetStatus().name(), event.failureCode(),
                signedAt, event.occurredAt());
    }
}
