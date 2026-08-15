package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.dto.PaymentWebhookResponse;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.model.PaymentWebhookOutcome;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import com.msb.ecom.payment_service.repository.PaymentProviderEventRecord;
import com.msb.ecom.payment_service.repository.PaymentRefundRepository;
import com.msb.ecom.payment_service.repository.PaymentReturnRefundRepository;
import com.msb.ecom.payment_service.webhook.PaymentWebhookAdapter;
import com.msb.ecom.payment_service.webhook.FakePaymentWebhookAdapter;
import com.msb.ecom.payment_service.webhook.FakePaymentWebhookEventParser;
import com.msb.ecom.payment_service.webhook.HmacWebhookVerifier;
import com.msb.ecom.payment_service.webhook.VerifiedPaymentProviderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);
    private final PaymentIntentRepository intents;
    private final PaymentRefundRepository refunds;
    private final PaymentReturnRefundRepository returnRefunds;
    private final Map<String, PaymentWebhookAdapter> adapters;
    private final PaymentUlidGenerator ids;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final boolean enabled;

    @Autowired
    public PaymentWebhookService(
            PaymentIntentRepository intents,
            PaymentRefundRepository refunds,
            PaymentReturnRefundRepository returnRefunds,
            List<PaymentWebhookAdapter> adapters,
            PaymentUlidGenerator ids,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            @Value("${payment.webhooks.enabled:false}") boolean enabled) {
        this.intents = intents;
        this.refunds = refunds;
        this.returnRefunds = returnRefunds;
        this.adapters = adapters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                PaymentWebhookAdapter::providerName, adapter -> adapter));
        this.ids = ids;
        this.transactions = transactions;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    // Preserves the deterministic test constructor while production uses provider adapters.
    public PaymentWebhookService(
            PaymentIntentRepository intents,
            FakePaymentWebhookEventParser parser,
            HmacWebhookVerifier verifier,
            PaymentUlidGenerator ids,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            boolean enabled) {
        this(intents, null, null, List.of(new FakePaymentWebhookAdapter(parser, verifier)),
                ids, transactions, mapper, enabled);
    }

    public PaymentWebhookResponse process(
            String signatureHeader, byte[] rawBody, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MSB-Signature", signatureHeader);
        return process("FAKE_LOCAL_DEMO_V1", headers, rawBody, correlationId);
    }

    // Verifies, deduplicates, and atomically applies one provider-neutral event.
    public PaymentWebhookResponse process(
            String provider, HttpHeaders headers, byte[] rawBody, String correlationId) {
        if (!enabled) {
            throw error(HttpStatus.NOT_FOUND, "PAYMENT_WEBHOOKS_DISABLED",
                    "Payment webhooks are not available.");
        }
        PaymentWebhookAdapter adapter = adapters.get(provider);
        if (adapter == null) {
            throw error(HttpStatus.NOT_FOUND, "PAYMENT_WEBHOOK_PROVIDER_NOT_FOUND",
                    "Payment webhook provider is not available.");
        }
        Instant receivedAt = Instant.now();
        VerifiedPaymentProviderEvent event = adapter.verifyAndParse(rawBody, headers, receivedAt);
        String payloadHash = sha256(rawBody);
        PaymentProviderEventRecord existing = intents.providerEvent(provider, event.eventId()).orElse(null);
        if (existing != null) {
            return replay(existing, payloadHash);
        }
        try {
            PaymentWebhookResponse result = transactions.execute(status -> apply(
                    event, payloadHash, receivedAt, correlationId));
            if (result == null) {
                throw new IllegalStateException("Payment webhook transaction returned no result.");
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            PaymentProviderEventRecord raced = intents.providerEvent(provider, event.eventId()).orElse(null);
            if (raced != null) {
                return replay(raced, payloadHash);
            }
            throw exception;
        }
    }

    private PaymentWebhookResponse apply(
            VerifiedPaymentProviderEvent event, String payloadHash,
            Instant receivedAt, String correlationId) {
        return switch (event.kind()) {
            case PAYMENT_ACTION_REQUIRED, PAYMENT_PROCESSING, PAYMENT_SUCCEEDED, PAYMENT_FAILED ->
                    applyPayment(event, payloadHash, receivedAt, correlationId);
            case REFUND_PROCESSING, REFUND_SUCCEEDED, REFUND_FAILED ->
                    applyRefund(event, payloadHash, receivedAt, correlationId);
            case UNSUPPORTED -> recordUnsupported(event, payloadHash, receivedAt, correlationId);
        };
    }

    private PaymentWebhookResponse applyPayment(
            VerifiedPaymentProviderEvent event, String payloadHash,
            Instant receivedAt, String correlationId) {
        PaymentIntent current = intents.findByProviderReferenceForUpdate(
                event.provider(), event.providerObjectReference()).orElse(null);
        if (current == null) {
            insertEvent(event, payloadHash, null, null,
                    PaymentWebhookOutcome.UNKNOWN_INTENT, null,
                    "PAYMENT_INTENT_NOT_FOUND", correlationId, receivedAt);
            return response(event.eventId(), null, null, PaymentWebhookOutcome.UNKNOWN_INTENT, false);
        }
        PaymentIntentStatus target = paymentTarget(event.kind());
        if (current.status() == PaymentIntentStatus.SUCCEEDED
                || current.status() == PaymentIntentStatus.FAILED) {
            PaymentWebhookOutcome outcome = current.status() == target
                    ? PaymentWebhookOutcome.IGNORED_LATE_EVENT
                    : PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION;
            insertEvent(event, payloadHash, current.id(), null, outcome,
                    current.status().name(), outcome == PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION
                            ? "PAYMENT_WEBHOOK_ILLEGAL_TRANSITION" : null,
                    correlationId, receivedAt);
            intents.insertWebhookAttempt(ids.next(), current.id(), intents.nextAttemptNumber(current.id()),
                    current.provider(), event.kind().name(), current.providerReference(),
                    outcome == PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION
                            ? "PAYMENT_WEBHOOK_ILLEGAL_TRANSITION" : null,
                    outcome == PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION
                            ? "Payment provider event cannot change the terminal payment state." : null,
                    receivedAt);
            return response(event.eventId(), current.id(), current.status(), outcome, false);
        }
        if (current.status() == target) {
            insertEvent(event, payloadHash, current.id(), null,
                    PaymentWebhookOutcome.APPLIED, current.status().name(),
                    event.safeFailureCode(), correlationId, receivedAt);
            intents.insertWebhookAttempt(ids.next(), current.id(), intents.nextAttemptNumber(current.id()),
                    current.provider(), event.kind().name(), current.providerReference(),
                    event.safeFailureCode(), safeFailureMessage(event.safeFailureCode()), receivedAt);
            return response(event.eventId(), current.id(), current.status(),
                    PaymentWebhookOutcome.APPLIED, false);
        }
        if (!current.status().canTransitionTo(target)) {
            insertEvent(event, payloadHash, current.id(), null,
                    PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION, current.status().name(),
                    "PAYMENT_WEBHOOK_ILLEGAL_TRANSITION", correlationId, receivedAt);
            intents.insertWebhookAttempt(ids.next(), current.id(), intents.nextAttemptNumber(current.id()),
                    current.provider(), event.kind().name(), current.providerReference(),
                    "PAYMENT_WEBHOOK_ILLEGAL_TRANSITION",
                    "Payment provider event cannot apply from the current payment state.", receivedAt);
            return response(event.eventId(), current.id(), current.status(),
                    PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION, false);
        }
        String failureCode = target == PaymentIntentStatus.FAILED
                ? safeFailureCode(event.safeFailureCode()) : null;
        PaymentIntent next = current.transition(
                target, current.providerReference(),
                target == PaymentIntentStatus.REQUIRES_ACTION ? current.providerActionType() : null,
                failureCode, safeFailureMessage(failureCode), receivedAt);
        insertEvent(event, payloadHash, current.id(), null, PaymentWebhookOutcome.APPLIED,
                target.name(), failureCode, correlationId, receivedAt);
        if (intents.transitionAs(current, next, ids.next(), "PROVIDER_" + event.kind().name(),
                actorScope(event.provider()), correlationId) != 1) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_WEBHOOK_CONCURRENT_CONFLICT",
                    "Payment webhook could not apply the provider state.");
        }
        intents.insertWebhookAttempt(ids.next(), current.id(), intents.nextAttemptNumber(current.id()),
                current.provider(), event.kind().name(), current.providerReference(),
                failureCode, safeFailureMessage(failureCode), receivedAt);
        if (target == PaymentIntentStatus.SUCCEEDED || target == PaymentIntentStatus.FAILED) {
            intents.insertOutbox(ids.next(), current.id(),
                    target == PaymentIntentStatus.SUCCEEDED ? "payment.succeeded" : "payment.failed",
                    paymentOutboxPayload(current, target, event.eventId()), correlationId,
                    event.eventId(), event.occurredAt(), receivedAt);
        }
        log.info("Payment webhook applied provider={} eventId={} paymentIntentId={} status={}",
                event.provider(), event.eventId(), current.id(), target);
        return response(event.eventId(), current.id(), target, PaymentWebhookOutcome.APPLIED, false);
    }

    private String actorScope(String provider) {
        return "FAKE_LOCAL_DEMO_V1".equals(provider) ? "FAKE_PROVIDER" : provider;
    }

    private PaymentWebhookResponse applyRefund(
            VerifiedPaymentProviderEvent event, String payloadHash,
            Instant receivedAt, String correlationId) {
        var full = refunds.findByProviderReference(
                event.provider(), event.providerObjectReference()).orElse(null);
        var partial = returnRefunds.findByProviderReference(
                event.provider(), event.providerObjectReference()).orElse(null);
        if (full == null && partial == null) {
            insertEvent(event, payloadHash, null, null,
                    PaymentWebhookOutcome.UNKNOWN_REFERENCE, null,
                    "PAYMENT_REFUND_NOT_FOUND", correlationId, receivedAt);
            return response(event.eventId(), null, null,
                    PaymentWebhookOutcome.UNKNOWN_REFERENCE, false);
        }
        String paymentIntentId = full != null ? full.paymentIntentId() : partial.paymentIntentId();
        String refundId = full != null ? full.id() : partial.id();
        var intent = refunds.lockIntent(paymentIntentId).orElseThrow();
        if (event.relatedPaymentReference() != null
                && !event.relatedPaymentReference().equals(intent.providerReference())) {
            insertEvent(event, payloadHash, paymentIntentId, refundId,
                    PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION, null,
                    "PAYMENT_REFUND_CORRELATION_CONFLICT", correlationId, receivedAt);
            return response(event.eventId(), paymentIntentId, null,
                    PaymentWebhookOutcome.REJECTED_ILLEGAL_TRANSITION, false);
        }
        String status = refundTarget(event.kind());
        insertEvent(event, payloadHash, paymentIntentId, refundId,
                PaymentWebhookOutcome.APPLIED, status, event.safeFailureCode(),
                correlationId, receivedAt);
        String payload = full != null
                ? fullRefundPayload(full, status, receivedAt)
                : returnRefundPayload(partial, status, receivedAt);
        if (full != null) {
            refunds.applyProviderResult(full.id(), status, full.providerReference(),
                    event.safeFailureCode(), "RETRIEVE_REFUND", correlationId,
                    ids.next(), ids.next(), ids.next(), payload, receivedAt,
                    receivedAt.plus(15, ChronoUnit.SECONDS));
        } else {
            returnRefunds.applyProviderResult(partial.id(), status, partial.providerReference(),
                    event.safeFailureCode(), "RETRIEVE_REFUND", correlationId,
                    ids.next(), ids.next(), payload, receivedAt,
                    receivedAt.plus(15, ChronoUnit.SECONDS));
        }
        return response(event.eventId(), paymentIntentId, null,
                PaymentWebhookOutcome.APPLIED, false);
    }

    private PaymentWebhookResponse recordUnsupported(
            VerifiedPaymentProviderEvent event, String payloadHash,
            Instant receivedAt, String correlationId) {
        insertEvent(event, payloadHash, null, null,
                PaymentWebhookOutcome.IGNORED_UNSUPPORTED_EVENT, null, null,
                correlationId, receivedAt);
        return response(event.eventId(), null, null,
                PaymentWebhookOutcome.IGNORED_UNSUPPORTED_EVENT, false);
    }

    private void insertEvent(
            VerifiedPaymentProviderEvent event, String payloadHash,
            String paymentIntentId, String refundId, PaymentWebhookOutcome outcome,
            String resultingStatus, String safeErrorCode, String correlationId, Instant receivedAt) {
        intents.insertProviderEvent(
                ids.next(), event.provider(), event.eventId(), event.eventType(),
                event.providerObjectReference(), paymentIntentId, refundId, payloadHash,
                event.signedAt(), event.occurredAt(), outcome, resultingStatus,
                safeErrorCode, correlationId, receivedAt);
    }

    private PaymentWebhookResponse replay(PaymentProviderEventRecord existing, String payloadHash) {
        if (!MessageDigest.isEqual(existing.payloadHash().getBytes(StandardCharsets.US_ASCII),
                payloadHash.getBytes(StandardCharsets.US_ASCII))) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_WEBHOOK_EVENT_CONFLICT",
                    "Provider event ID was already used with a different payload.");
        }
        return new PaymentWebhookResponse(
                existing.providerEventId(), existing.paymentIntentId(),
                existing.resultingStatus(), existing.outcome().name(), true);
    }

    private PaymentIntentStatus paymentTarget(VerifiedPaymentProviderEvent.Kind kind) {
        return switch (kind) {
            case PAYMENT_ACTION_REQUIRED -> PaymentIntentStatus.REQUIRES_ACTION;
            case PAYMENT_PROCESSING -> PaymentIntentStatus.PROCESSING;
            case PAYMENT_SUCCEEDED -> PaymentIntentStatus.SUCCEEDED;
            case PAYMENT_FAILED -> PaymentIntentStatus.FAILED;
            default -> throw new IllegalArgumentException("Not a payment event.");
        };
    }

    private String refundTarget(VerifiedPaymentProviderEvent.Kind kind) {
        return switch (kind) {
            case REFUND_PROCESSING -> "PROCESSING";
            case REFUND_SUCCEEDED -> "SUCCEEDED";
            case REFUND_FAILED -> "FAILED";
            default -> throw new IllegalArgumentException("Not a refund event.");
        };
    }

    private String paymentOutboxPayload(
            PaymentIntent intent, PaymentIntentStatus target, String providerEventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", intent.id());
        payload.put("checkoutId", intent.checkoutId());
        payload.put("status", target.name());
        payload.put("amount", intent.amount().toPlainString());
        payload.put("currency", intent.currency());
        payload.put("providerEventId", providerEventId);
        return json(payload);
    }

    private String fullRefundPayload(
            PaymentRefundRepository.RefundRecord record, String status, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", record.id());
        payload.put("paymentIntentId", record.paymentIntentId());
        payload.put("orderId", record.orderId());
        payload.put("cancellationRequestId", record.cancellationRequestId());
        payload.put("amount", record.amount());
        payload.put("currency", record.currency());
        payload.put("status", status);
        payload.put("occurredAt", now);
        return json(payload);
    }

    private String returnRefundPayload(
            PaymentReturnRefundRepository.Record record, String status, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", record.id());
        payload.put("returnId", record.returnId());
        payload.put("orderId", record.orderId());
        payload.put("businessOrderId", record.businessOrderId());
        payload.put("amount", record.amount());
        payload.put("currency", record.currency());
        payload.put("status", status);
        payload.put("occurredAt", now);
        return json(payload);
    }

    private String safeFailureCode(String providerCode) {
        return switch (providerCode == null ? "" : providerCode) {
            case "CARD_DECLINED" -> "CARD_DECLINED";
            case "AUTHENTICATION_FAILED" -> "AUTHENTICATION_FAILED";
            case "PROCESSING_ERROR" -> "PROCESSING_ERROR";
            default -> "PAYMENT_FAILED";
        };
    }

    private String safeFailureMessage(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "CARD_DECLINED" -> "Payment method was declined.";
            case "AUTHENTICATION_FAILED" -> "Payment authentication failed.";
            case "PROCESSING_ERROR" -> "Payment processing failed.";
            case "PAYMENT_METHOD_REQUIRED" -> "Another payment method is required.";
            default -> "Payment failed.";
        };
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment event serialization failed.", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private PaymentWebhookResponse response(
            String eventId, String paymentIntentId, PaymentIntentStatus status,
            PaymentWebhookOutcome outcome, boolean replayed) {
        return new PaymentWebhookResponse(eventId, paymentIntentId,
                status == null ? null : status.name(), outcome.name(), replayed);
    }

    private PaymentIntentException error(HttpStatus status, String code, String message) {
        return new PaymentIntentException(status, code, message);
    }
}
