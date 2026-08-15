package com.msb.ecom.payment_service.webhook;

import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.provider.StripeTestPaymentProvider;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = StripeTestPaymentProvider.PROVIDER)
public class StripePaymentWebhookAdapter implements PaymentWebhookAdapter {

    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private final String webhookSecret;

    public StripePaymentWebhookAdapter(
            @Value("${payment.stripe.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String providerName() {
        return StripeTestPaymentProvider.PROVIDER;
    }

    // Verifies Stripe's signature over the exact raw body before inspecting provider objects.
    @Override
    public VerifiedPaymentProviderEvent verifyAndParse(
            byte[] rawBody, HttpHeaders headers, Instant receivedAt) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_PAYLOAD_BYTES) {
            throw invalid("PAYMENT_WEBHOOK_PAYLOAD_INVALID", HttpStatus.BAD_REQUEST);
        }
        String signature = headers.getFirst("Stripe-Signature");
        Event event;
        try {
            event = Webhook.constructEvent(
                    new String(rawBody, StandardCharsets.UTF_8), signature, webhookSecret);
        } catch (SignatureVerificationException | RuntimeException exception) {
            throw invalid("PAYMENT_WEBHOOK_SIGNATURE_INVALID", HttpStatus.FORBIDDEN);
        }
        Instant signedAt = signatureTimestamp(signature, receivedAt);
        Instant occurredAt = Instant.ofEpochSecond(event.getCreated());
        StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
        if (object instanceof PaymentIntent intent) {
            return paymentEvent(event, intent, signedAt, occurredAt);
        }
        if (object instanceof Refund refund) {
            return refundEvent(event, refund, signedAt, occurredAt);
        }
        String reference = "unsupported";
        return new VerifiedPaymentProviderEvent(
                providerName(), event.getId(), event.getType(),
                VerifiedPaymentProviderEvent.Kind.UNSUPPORTED,
                reference == null ? "unsupported" : reference, null, null, null,
                signedAt, occurredAt);
    }

    private VerifiedPaymentProviderEvent paymentEvent(
            Event event, PaymentIntent intent, Instant signedAt, Instant occurredAt) {
        VerifiedPaymentProviderEvent.Kind kind = switch (event.getType()) {
            case "payment_intent.succeeded" -> VerifiedPaymentProviderEvent.Kind.PAYMENT_SUCCEEDED;
            case "payment_intent.processing" -> VerifiedPaymentProviderEvent.Kind.PAYMENT_PROCESSING;
            case "payment_intent.payment_failed" ->
                    VerifiedPaymentProviderEvent.Kind.PAYMENT_ACTION_REQUIRED;
            case "payment_intent.canceled" -> VerifiedPaymentProviderEvent.Kind.PAYMENT_FAILED;
            default -> VerifiedPaymentProviderEvent.Kind.UNSUPPORTED;
        };
        String failure = intent.getLastPaymentError() == null
                ? null : safeFailure(intent.getLastPaymentError().getCode());
        return new VerifiedPaymentProviderEvent(
                providerName(), event.getId(), event.getType(), kind, intent.getId(), null,
                intent.getStatus(), failure, signedAt, occurredAt);
    }

    private VerifiedPaymentProviderEvent refundEvent(
            Event event, Refund refund, Instant signedAt, Instant occurredAt) {
        VerifiedPaymentProviderEvent.Kind kind = switch (refund.getStatus()) {
            case "pending", "requires_action" -> VerifiedPaymentProviderEvent.Kind.REFUND_PROCESSING;
            case "succeeded" -> VerifiedPaymentProviderEvent.Kind.REFUND_SUCCEEDED;
            case "failed", "canceled" -> VerifiedPaymentProviderEvent.Kind.REFUND_FAILED;
            default -> VerifiedPaymentProviderEvent.Kind.UNSUPPORTED;
        };
        return new VerifiedPaymentProviderEvent(
                providerName(), event.getId(), event.getType(), kind, refund.getId(),
                refund.getPaymentIntent(), refund.getStatus(),
                kind == VerifiedPaymentProviderEvent.Kind.REFUND_FAILED
                        ? "PROVIDER_REFUND_FAILED" : null,
                signedAt, occurredAt);
    }

    private Instant signatureTimestamp(String signature, Instant fallback) {
        if (signature == null) {
            return fallback;
        }
        for (String part : signature.split(",")) {
            if (part.startsWith("t=")) {
                try {
                    return Instant.ofEpochSecond(Long.parseLong(part.substring(2)));
                } catch (RuntimeException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private String safeFailure(String providerCode) {
        if (providerCode == null) {
            return "PAYMENT_METHOD_REQUIRED";
        }
        return switch (providerCode) {
            case "card_declined" -> "CARD_DECLINED";
            case "authentication_required" -> "AUTHENTICATION_FAILED";
            case "processing_error" -> "PROCESSING_ERROR";
            default -> "PAYMENT_METHOD_REQUIRED";
        };
    }

    private PaymentIntentException invalid(String code, HttpStatus status) {
        return new PaymentIntentException(status, code, "Payment webhook could not be verified.");
    }
}
