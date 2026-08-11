package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.dto.CompleteDemoPaymentRequest;
import com.msb.ecom.payment_service.dto.PaymentWebhookResponse;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DemoPaymentCompletionService {

    private final PaymentIntentRepository repository;
    private final InternalPaymentAuthenticator authenticator;
    private final PaymentWebhookService webhooks;
    private final ObjectMapper objectMapper;
    private final byte[] webhookSecret;
    private final boolean enabled;

    public DemoPaymentCompletionService(
            PaymentIntentRepository repository,
            InternalPaymentAuthenticator authenticator,
            PaymentWebhookService webhooks,
            ObjectMapper objectMapper,
            @Value("${payment.webhooks.fake.secret:}") String webhookSecret,
            @Value("${payment.demo-completion.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.authenticator = authenticator;
        this.webhooks = webhooks;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.enabled = enabled;
    }

    // Simulates the local provider while still traversing the verified webhook boundary.
    public PaymentWebhookResponse complete(
            String internalToken,
            String paymentIntentId,
            CompleteDemoPaymentRequest request,
            String correlationId) {
        if (!enabled) {
            throw new PaymentIntentException(
                    HttpStatus.NOT_FOUND,
                    "DEMO_PAYMENT_COMPLETION_DISABLED",
                    "Demo payment completion is not available.");
        }
        authenticator.requireAuthenticated(internalToken);
        PaymentIntent intent = repository.findOwned(paymentIntentId, request.buyerId())
                .orElseThrow(() -> new PaymentIntentException(
                        HttpStatus.NOT_FOUND,
                        "PAYMENT_INTENT_NOT_FOUND",
                        "Payment intent was not found."));
        String expectedAction = "fake_action_" + paymentIntentId.toLowerCase();
        if (!expectedAction.equals(request.actionReference())) {
            throw new PaymentIntentException(
                    HttpStatus.CONFLICT,
                    "DEMO_PAYMENT_ACTION_INVALID",
                    "Demo payment action does not match the payment intent.");
        }
        if (!intent.expiresAt().isAfter(Instant.now())) {
            throw new PaymentIntentException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_INTENT_EXPIRED",
                    "The checkout payment window has expired.");
        }
        if (intent.status() == PaymentIntentStatus.FAILED) {
            throw new PaymentIntentException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_INTENT_TERMINAL",
                    "The payment intent is already complete.");
        }
        if (webhookSecret.length == 0) {
            throw new PaymentIntentException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_WEBHOOK_NOT_CONFIGURED",
                    "Payment webhook verification is not configured.");
        }

        byte[] body = payload(intent);
        long timestamp = Instant.now().getEpochSecond();
        String signature = "t=" + timestamp + ",v1=" + sign(timestamp, body);
        return webhooks.process(signature, body, correlationId);
    }

    private byte[] payload(PaymentIntent intent) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("eventId", "fake_evt_complete_" + intent.id().toLowerCase());
        root.put("type", "payment_intent.succeeded");
        root.put("occurredAt", intent.createdAt().toString());
        root.put("data", Map.of("providerReference", intent.providerReference()));
        try {
            return objectMapper.writeValueAsBytes(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Demo payment event could not be serialized.", exception);
        }
    }

    private String sign(long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", exception);
        }
    }
}
