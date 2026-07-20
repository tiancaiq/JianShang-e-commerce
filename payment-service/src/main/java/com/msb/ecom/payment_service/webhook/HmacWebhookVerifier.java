package com.msb.ecom.payment_service.webhook;

import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HmacWebhookVerifier {

    private static final Pattern SIGNATURE =
            Pattern.compile("t=([0-9]{10}),v1=([a-f0-9]{64})");

    private final byte[] secret;
    private final Duration tolerance;

    public HmacWebhookVerifier(
            @Value("${payment.webhooks.fake.secret:}") String secret,
            @Value("${payment.webhooks.signature-tolerance:PT5M}") Duration tolerance) {
        if (tolerance.isZero() || tolerance.isNegative() || tolerance.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("Webhook signature tolerance must be between PT0S and PT15M.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
    }

    // Authenticates the exact raw body with a fresh timestamp and constant-time digest comparison.
    public Instant verify(String signatureHeader, byte[] rawBody, Instant now) {
        if (secret.length == 0) {
            throw new PaymentIntentException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_WEBHOOK_NOT_CONFIGURED",
                    "Payment webhook verification is not configured.");
        }
        Matcher matcher = signatureHeader == null ? null : SIGNATURE.matcher(signatureHeader);
        if (matcher == null || !matcher.matches()) {
            throw invalidSignature();
        }
        Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(matcher.group(1)));
        } catch (RuntimeException exception) {
            throw invalidSignature();
        }
        Duration age = Duration.between(signedAt, now).abs();
        if (age.compareTo(tolerance) > 0) {
            throw new PaymentIntentException(
                    HttpStatus.FORBIDDEN,
                    "PAYMENT_WEBHOOK_TIMESTAMP_INVALID",
                    "Payment webhook signature timestamp is outside the allowed tolerance.");
        }
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(matcher.group(2));
        } catch (IllegalArgumentException exception) {
            throw invalidSignature();
        }
        byte[] expected = hmac(matcher.group(1), rawBody);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw invalidSignature();
        }
        return signedAt;
    }

    private byte[] hmac(String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", exception);
        }
    }

    private PaymentIntentException invalidSignature() {
        return new PaymentIntentException(
                HttpStatus.FORBIDDEN,
                "PAYMENT_WEBHOOK_SIGNATURE_INVALID",
                "Payment webhook signature is invalid.");
    }
}
