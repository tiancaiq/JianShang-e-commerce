package com.msb.ecom.payment_service.provider;

import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.stripe.StripeClient;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.net.URI;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = StripeTestPaymentProvider.PROVIDER)
public class StripeTestPaymentProvider implements PaymentProvider {

    public static final String PROVIDER = "STRIPE_TEST_V1";

    private final boolean externalProviderEnabled;
    private final boolean sandboxEnabled;
    private final String secretKey;
    private final String publishableKey;
    private final String webhookSecret;
    private final String returnUrlBase;
    private final String apiVersion;
    private StripeClient client;

    public StripeTestPaymentProvider(
            @Value("${payment.external-provider-enabled:false}") boolean externalProviderEnabled,
            @Value("${payment.stripe.sandbox-enabled:false}") boolean sandboxEnabled,
            @Value("${payment.stripe.secret-key:}") String secretKey,
            @Value("${payment.stripe.publishable-key:}") String publishableKey,
            @Value("${payment.stripe.webhook-secret:}") String webhookSecret,
            @Value("${payment.stripe.return-url-base:http://localhost:4200/checkout}") String returnUrlBase,
            @Value("${payment.stripe.api-version:2026-02-25.clover}") String apiVersion) {
        this.externalProviderEnabled = externalProviderEnabled;
        this.sandboxEnabled = sandboxEnabled;
        this.secretKey = secretKey;
        this.publishableKey = publishableKey;
        this.webhookSecret = webhookSecret;
        this.returnUrlBase = returnUrlBase;
        this.apiVersion = apiVersion;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!externalProviderEnabled) {
            throw new IllegalStateException("Stripe test provider requires PAYMENT_EXTERNAL_PROVIDER_ENABLED=true.");
        }
        if (!sandboxEnabled) {
            throw new IllegalStateException("Stripe test provider requires PAYMENT_STRIPE_SANDBOX_ENABLED=true.");
        }
        if (!secretKey.startsWith("sk_test_") || secretKey.isBlank()) {
            throw new IllegalStateException("Stripe test provider requires a test-mode secret key.");
        }
        if (!publishableKey.startsWith("pk_test_") || publishableKey.isBlank()) {
            throw new IllegalStateException("Stripe test provider requires a test-mode publishable key.");
        }
        if (!webhookSecret.startsWith("whsec_") || webhookSecret.isBlank()) {
            throw new IllegalStateException("Stripe test provider requires a webhook signing secret.");
        }
        if (!Stripe.API_VERSION.equals(apiVersion)) {
            throw new IllegalStateException("Stripe API version must match the stripe-java SDK pin.");
        }
        URI returnUri = URI.create(returnUrlBase);
        if (!("https".equalsIgnoreCase(returnUri.getScheme())
                || ("http".equalsIgnoreCase(returnUri.getScheme())
                && "localhost".equalsIgnoreCase(returnUri.getHost())))) {
            throw new IllegalStateException("Stripe return URL must use HTTPS outside localhost.");
        }
        this.client = new StripeClient(secretKey);
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    // Creates one card-only Stripe PaymentIntent from the server-authoritative payment command.
    @Override
    public PaymentProviderResult createIntent(PaymentProviderCommand command) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(minorUnits(command.amount(), command.currency()))
                .setCurrency(command.currency().toLowerCase(Locale.ROOT))
                .addPaymentMethodType("card")
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                .putMetadata("internal_payment_id", command.paymentIntentId())
                .putMetadata("checkout_id", command.checkoutId())
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(command.idempotencyKey())
                .build();
        try {
            return result(client.v1().paymentIntents().create(params, options));
        } catch (StripeException exception) {
            throw new PaymentProviderException("Stripe PaymentIntent creation failed.", exception);
        }
    }

    @Override
    public PaymentProviderResult retrieveIntent(String providerReference) {
        try {
            return result(client.v1().paymentIntents().retrieve(providerReference));
        } catch (StripeException exception) {
            throw new PaymentProviderException("Stripe PaymentIntent retrieval failed.", exception);
        }
    }

    @Override
    public String actionReference(String paymentIntentId) {
        return null;
    }

    @Override
    public String actionReference(String paymentIntentId, String providerReference) {
        if (providerReference == null || providerReference.isBlank()) {
            return null;
        }
        try {
            return client.v1().paymentIntents().retrieve(providerReference).getClientSecret();
        } catch (StripeException exception) {
            throw new PaymentProviderException("Stripe client action retrieval failed.", exception);
        }
    }

    @Override
    public String publicClientKey() {
        return publishableKey;
    }

    @Override
    public String returnUrl(String checkoutId) {
        return returnUrlBase.replaceAll("/+$", "") + "/" + checkoutId;
    }

    @Override
    public PaymentRefundResult refund(PaymentRefundCommand command) {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(command.providerPaymentReference())
                .setAmount(minorUnits(command.amount(), command.currency()))
                .putMetadata("internal_refund_id", command.refundId())
                .putMetadata("internal_payment_id", command.paymentIntentId())
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey("payment:refund:" + command.refundId() + ":v1")
                .build();
        try {
            return refundResult(client.v1().refunds().create(params, options));
        } catch (StripeException exception) {
            throw new PaymentProviderException("Stripe refund creation failed.", exception);
        }
    }

    @Override
    public PaymentRefundResult retrieveRefund(String providerReference) {
        try {
            return refundResult(client.v1().refunds().retrieve(providerReference));
        } catch (StripeException exception) {
            throw new PaymentProviderException("Stripe refund retrieval failed.", exception);
        }
    }

    String webhookSecret() {
        return webhookSecret;
    }

    private PaymentProviderResult result(PaymentIntent intent) {
        PaymentIntentStatus status = switch (intent.getStatus()) {
            case "requires_payment_method", "requires_confirmation", "requires_action" ->
                    PaymentIntentStatus.REQUIRES_ACTION;
            case "processing" -> PaymentIntentStatus.PROCESSING;
            case "succeeded" -> PaymentIntentStatus.SUCCEEDED;
            case "canceled" -> PaymentIntentStatus.FAILED;
            default -> throw new PaymentProviderException("Unsupported Stripe PaymentIntent status.");
        };
        String actionType = switch (status) {
            case REQUIRES_ACTION -> "STRIPE_PAYMENT_ELEMENT";
            default -> null;
        };
        String safeCode = "canceled".equals(intent.getStatus()) ? "PROVIDER_CANCELED" : null;
        return new PaymentProviderResult(
                status,
                intent.getId(),
                actionType,
                safeCode,
                safeCode == null ? null : "Payment was canceled.");
    }

    private PaymentRefundResult refundResult(Refund refund) {
        String status = switch (refund.getStatus()) {
            case "pending", "requires_action" -> "PROCESSING";
            case "succeeded" -> "SUCCEEDED";
            case "failed", "canceled" -> "FAILED";
            default -> throw new PaymentProviderException("Unsupported Stripe refund status.");
        };
        return new PaymentRefundResult(status, refund.getId());
    }

    private long minorUnits(BigDecimal amount, String currency) {
        if (!"USD".equals(currency)) {
            throw new PaymentProviderException("Stripe test milestone supports USD only.");
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw new PaymentProviderException("Payment amount is not representable in currency minor units.", exception);
        }
    }
}
