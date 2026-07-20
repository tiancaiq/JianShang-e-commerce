package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.dto.PaymentWebhookResponse;
import com.msb.ecom.payment_service.model.FakePaymentWebhookEvent;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.model.PaymentWebhookOutcome;
import com.msb.ecom.payment_service.provider.DeterministicFakePaymentProvider;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import com.msb.ecom.payment_service.repository.PaymentProviderEventRecord;
import com.msb.ecom.payment_service.webhook.FakePaymentWebhookEventParser;
import com.msb.ecom.payment_service.webhook.HmacWebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);
    private static final String PROVIDER_ACTOR = "FAKE_PROVIDER";

    private final PaymentIntentRepository repository;
    private final FakePaymentWebhookEventParser parser;
    private final HmacWebhookVerifier verifier;
    private final PaymentUlidGenerator ids;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public PaymentWebhookService(
            PaymentIntentRepository repository,
            FakePaymentWebhookEventParser parser,
            HmacWebhookVerifier verifier,
            PaymentUlidGenerator ids,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            @Value("${payment.webhooks.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.parser = parser;
        this.verifier = verifier;
        this.ids = ids;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    // Verifies, deduplicates, and atomically records one provider-confirmed terminal outcome.
    public PaymentWebhookResponse process(
            String signatureHeader,
            byte[] rawBody,
            String correlationId) {
        if (!enabled) {
            throw new PaymentIntentException(
                    HttpStatus.NOT_FOUND,
                    "PAYMENT_WEBHOOKS_DISABLED",
                    "Payment webhooks are not available.");
        }
        parser.requireBounded(rawBody);
        Instant receivedAt = Instant.now();
        Instant signedAt = verifier.verify(signatureHeader, rawBody, receivedAt);
        FakePaymentWebhookEvent event = parser.parse(rawBody);
        String payloadHash = sha256(rawBody);

        PaymentProviderEventRecord existing = repository.providerEvent(
                        DeterministicFakePaymentProvider.PROVIDER,
                        event.eventId())
                .orElse(null);
        if (existing != null) {
            return replay(existing, payloadHash);
        }

        try {
            PaymentWebhookResponse result = transactions.execute(status -> apply(
                    event, payloadHash, signedAt, receivedAt, correlationId));
            if (result == null) {
                throw new IllegalStateException("Payment webhook transaction returned no result.");
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            PaymentProviderEventRecord raced = repository.providerEvent(
                            DeterministicFakePaymentProvider.PROVIDER,
                            event.eventId())
                    .orElse(null);
            if (raced != null) {
                return replay(raced, payloadHash);
            }
            throw exception;
        }
    }

    private PaymentWebhookResponse apply(
            FakePaymentWebhookEvent event,
            String payloadHash,
            Instant signedAt,
            Instant receivedAt,
            String correlationId) {
        PaymentIntent current = repository.findByProviderReferenceForUpdate(
                        DeterministicFakePaymentProvider.PROVIDER,
                        event.providerReference())
                .orElse(null);

        PaymentProviderEventRecord existing = repository.providerEvent(
                        DeterministicFakePaymentProvider.PROVIDER,
                        event.eventId())
                .orElse(null);
        if (existing != null) {
            return replay(existing, payloadHash);
        }

        if (current == null) {
            repository.insertProviderEvent(
                    ids.next(),
                    DeterministicFakePaymentProvider.PROVIDER,
                    event.eventId(),
                    event.type().wireName(),
                    event.providerReference(),
                    null,
                    payloadHash,
                    signedAt,
                    event.occurredAt(),
                    PaymentWebhookOutcome.UNKNOWN_INTENT,
                    null,
                    "PAYMENT_INTENT_NOT_FOUND",
                    correlationId,
                    receivedAt);
            log.info(
                    "Payment webhook processed providerEventId={} outcome={} correlationId={}",
                    event.eventId(),
                    PaymentWebhookOutcome.UNKNOWN_INTENT,
                    correlationId);
            return response(event.eventId(), null, null, PaymentWebhookOutcome.UNKNOWN_INTENT, false);
        }

        PaymentIntentStatus target = event.type().targetStatus();
        if (current.status() == PaymentIntentStatus.SUCCEEDED
                || current.status() == PaymentIntentStatus.FAILED) {
            PaymentWebhookOutcome outcome = current.status() == target
                    ? PaymentWebhookOutcome.IGNORED_LATE_EVENT
                    : PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION;
            recordNonTransitioningEvent(
                    event, payloadHash, signedAt, receivedAt, correlationId, current, outcome);
            return response(event.eventId(), current.id(), current.status(), outcome, false);
        }

        if (current.status() != PaymentIntentStatus.REQUIRES_ACTION
                && current.status() != PaymentIntentStatus.PROCESSING) {
            recordNonTransitioningEvent(
                    event,
                    payloadHash,
                    signedAt,
                    receivedAt,
                    correlationId,
                    current,
                    PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION);
            return response(
                    event.eventId(),
                    current.id(),
                    current.status(),
                    PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION,
                    false);
        }

        String safeErrorCode = target == PaymentIntentStatus.FAILED
                ? safeFailureCode(event.failureCode())
                : null;
        String safeErrorMessage = safeErrorCode == null
                ? null
                : safeFailureMessage(safeErrorCode);
        PaymentIntent next = current.transition(
                target,
                current.providerReference(),
                null,
                safeErrorCode,
                safeErrorMessage,
                receivedAt);

        repository.insertProviderEvent(
                ids.next(),
                current.provider(),
                event.eventId(),
                event.type().wireName(),
                event.providerReference(),
                current.id(),
                payloadHash,
                signedAt,
                event.occurredAt(),
                PaymentWebhookOutcome.APPLIED,
                target,
                safeErrorCode,
                correlationId,
                receivedAt);
        int changed = repository.transitionAs(
                current,
                next,
                ids.next(),
                target == PaymentIntentStatus.SUCCEEDED
                        ? "PROVIDER_PAYMENT_SUCCEEDED"
                        : "PROVIDER_PAYMENT_FAILED",
                PROVIDER_ACTOR,
                correlationId);
        if (changed != 1) {
            throw new PaymentIntentException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_WEBHOOK_CONCURRENT_CONFLICT",
                    "Payment webhook could not apply the provider state.");
        }
        repository.insertWebhookAttempt(
                ids.next(),
                current.id(),
                repository.nextAttemptNumber(current.id()),
                current.provider(),
                target.name(),
                current.providerReference(),
                safeErrorCode,
                safeErrorMessage,
                receivedAt);
        repository.insertOutbox(
                ids.next(),
                current.id(),
                target == PaymentIntentStatus.SUCCEEDED
                        ? "payment.succeeded"
                        : "payment.failed",
                outboxPayload(current, target, event.eventId()),
                correlationId,
                event.eventId(),
                event.occurredAt(),
                receivedAt);
        log.info(
                "Payment webhook processed providerEventId={} paymentIntentId={} outcome={} status={} correlationId={}",
                event.eventId(),
                current.id(),
                PaymentWebhookOutcome.APPLIED,
                target,
                correlationId);
        return response(event.eventId(), current.id(), target, PaymentWebhookOutcome.APPLIED, false);
    }

    private void recordNonTransitioningEvent(
            FakePaymentWebhookEvent event,
            String payloadHash,
            Instant signedAt,
            Instant receivedAt,
            String correlationId,
            PaymentIntent current,
            PaymentWebhookOutcome outcome) {
        String safeErrorCode = outcome == PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION
                ? "PAYMENT_WEBHOOK_ILLEGAL_TRANSITION"
                : null;
        repository.insertProviderEvent(
                ids.next(),
                current.provider(),
                event.eventId(),
                event.type().wireName(),
                event.providerReference(),
                current.id(),
                payloadHash,
                signedAt,
                event.occurredAt(),
                outcome,
                current.status(),
                safeErrorCode,
                correlationId,
                receivedAt);
        repository.insertWebhookAttempt(
                ids.next(),
                current.id(),
                repository.nextAttemptNumber(current.id()),
                current.provider(),
                outcome.name(),
                current.providerReference(),
                safeErrorCode,
                safeErrorCode == null ? null : "Provider event conflicts with payment state.",
                receivedAt);
        log.info(
                "Payment webhook processed providerEventId={} paymentIntentId={} outcome={} correlationId={}",
                event.eventId(),
                current.id(),
                outcome,
                correlationId);
    }

    private PaymentWebhookResponse replay(PaymentProviderEventRecord existing, String payloadHash) {
        if (!MessageDigest.isEqual(
                existing.payloadHash().getBytes(StandardCharsets.US_ASCII),
                payloadHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new PaymentIntentException(
                    HttpStatus.CONFLICT,
                    "PAYMENT_WEBHOOK_EVENT_CONFLICT",
                    "Provider event ID was already used with a different payload.");
        }
        return new PaymentWebhookResponse(
                existing.providerEventId(),
                existing.paymentIntentId(),
                existing.resultingStatus(),
                existing.outcome().name(),
                true);
    }

    private PaymentWebhookResponse response(
            String eventId,
            String paymentIntentId,
            PaymentIntentStatus status,
            PaymentWebhookOutcome outcome,
            boolean replayed) {
        return new PaymentWebhookResponse(
                eventId,
                paymentIntentId,
                status == null ? null : status.name(),
                outcome.name(),
                replayed);
    }

    private String outboxPayload(
            PaymentIntent intent,
            PaymentIntentStatus target,
            String providerEventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", intent.id());
        payload.put("checkoutId", intent.checkoutId());
        payload.put("status", target.name());
        payload.put("amount", intent.amount().toPlainString());
        payload.put("currency", intent.currency());
        payload.put("providerEventId", providerEventId);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment outbox payload serialization failed.", exception);
        }
    }

    private String safeFailureCode(String providerCode) {
        return switch (providerCode) {
            case "CARD_DECLINED" -> "CARD_DECLINED";
            case "AUTHENTICATION_FAILED" -> "AUTHENTICATION_FAILED";
            case "PROCESSING_ERROR" -> "PROCESSING_ERROR";
            default -> "PAYMENT_FAILED";
        };
    }

    private String safeFailureMessage(String code) {
        return switch (code) {
            case "CARD_DECLINED" -> "Payment method was declined.";
            case "AUTHENTICATION_FAILED" -> "Payment authentication failed.";
            case "PROCESSING_ERROR" -> "Payment processing failed.";
            default -> "Payment failed.";
        };
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
