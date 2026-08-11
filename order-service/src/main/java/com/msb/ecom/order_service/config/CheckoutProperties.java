package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CheckoutProperties {

    public static final String LOCAL_TAX_ADAPTER = "ZERO_LOCAL_DEMO_V1";
    public static final String LOCAL_SHIPPING_ADAPTER = "FREE_LOCAL_DEMO_V1";
    public static final String LOCAL_POLICY_VERSION = "LOCAL_DEMO_V1";
    public static final String LOCAL_CANCELLATION_POLICY_VERSION = "LOCAL_DEMO_CANCELLATION_V1";

    private final boolean enabled;
    private final String calculationMode;
    private final String taxAdapter;
    private final String shippingAdapter;
    private final String policyVersion;
    private final Duration sessionLifetime;
    private final boolean expiryWorkerEnabled;
    private final int expiryBatchSize;
    private final int recoveryBatchSize;
    private final Duration idempotencyRetention;

    public CheckoutProperties(
            Environment environment,
            @Value("${checkout.enabled:false}") boolean enabled,
            @Value("${checkout.calculation-mode:LOCAL_DEMO}") String calculationMode,
            @Value("${checkout.tax-adapter:ZERO_LOCAL_DEMO_V1}") String taxAdapter,
            @Value("${checkout.shipping-adapter:FREE_LOCAL_DEMO_V1}") String shippingAdapter,
            @Value("${checkout.policy-version:LOCAL_DEMO_V1}") String policyVersion,
            @Value("${checkout.session-lifetime:PT15M}") Duration sessionLifetime,
            @Value("${checkout.expiry-worker-enabled:true}") boolean expiryWorkerEnabled,
            @Value("${checkout.expiry-batch-size:50}") int expiryBatchSize,
            @Value("${checkout.recovery-batch-size:50}") int recoveryBatchSize,
            @Value("${checkout.idempotency-retention:P7D}") Duration idempotencyRetention) {
        this.enabled = enabled;
        this.calculationMode = required(calculationMode, "Checkout calculation mode");
        this.taxAdapter = required(taxAdapter, "Checkout tax adapter");
        this.shippingAdapter = required(shippingAdapter, "Checkout shipping adapter");
        this.policyVersion = required(policyVersion, "Checkout policy version");
        this.sessionLifetime = positive(sessionLifetime, "Checkout session lifetime");
        this.expiryWorkerEnabled = expiryWorkerEnabled;
        this.expiryBatchSize = batch(expiryBatchSize, "Checkout expiry batch size");
        this.recoveryBatchSize = batch(recoveryBatchSize, "Checkout recovery batch size");
        this.idempotencyRetention = positive(idempotencyRetention, "Checkout idempotency retention");

        if (!"LOCAL_DEMO".equals(this.calculationMode)
                || !LOCAL_TAX_ADAPTER.equals(this.taxAdapter)
                || !LOCAL_SHIPPING_ADAPTER.equals(this.shippingAdapter)
                || !(LOCAL_POLICY_VERSION.equals(this.policyVersion)
                        || LOCAL_CANCELLATION_POLICY_VERSION.equals(this.policyVersion))) {
            throw new IllegalArgumentException("Only the approved local-demo checkout configuration is implemented.");
        }
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("Local-demo checkout adapters are forbidden in production profiles.");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value.trim();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static int batch(int value, String name) {
        if (value < 1 || value > 500) {
            throw new IllegalArgumentException(name + " must be from 1 through 500.");
        }
        return value;
    }

    public boolean enabled() {
        return enabled;
    }

    public String taxAdapter() {
        return taxAdapter;
    }

    public String shippingAdapter() {
        return shippingAdapter;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public Duration sessionLifetime() {
        return sessionLifetime;
    }

    public boolean expiryWorkerEnabled() {
        return expiryWorkerEnabled;
    }

    public int expiryBatchSize() {
        return expiryBatchSize;
    }

    public int recoveryBatchSize() {
        return recoveryBatchSize;
    }

    public Duration idempotencyRetention() {
        return idempotencyRetention;
    }
}
