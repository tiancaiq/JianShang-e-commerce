package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.config.PaymentRefundProperties;
import com.msb.ecom.payment_service.dto.CreatePaymentRefundRequest;
import com.msb.ecom.payment_service.dto.PaymentRefundResponse;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.provider.DeterministicFakePaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentRefundCommand;
import com.msb.ecom.payment_service.provider.PaymentRefundResult;
import com.msb.ecom.payment_service.repository.PaymentRefundRepository;
import com.msb.ecom.payment_service.repository.PaymentRefundRepository.RefundRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PaymentRefundService {

    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private final PaymentRefundProperties properties;
    private final InternalPaymentAuthenticator authenticator;
    private final PaymentRefundRepository repository;
    private final Map<String, PaymentProvider> providers;
    private final PaymentUlidGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PaymentRefundService(
            PaymentRefundProperties properties,
            InternalPaymentAuthenticator authenticator,
            PaymentRefundRepository repository,
            List<PaymentProvider> providers,
            PaymentUlidGenerator ids,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.repository = repository;
        this.providers = providers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                PaymentProvider::providerName, provider -> provider));
        this.ids = ids;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = Clock.systemUTC();
    }

    // Reserves exactly one full refund before invoking a potentially asynchronous provider.
    public PaymentRefundResponse refund(
            String internalToken,
            String paymentIntentId,
            String idempotencyKey,
            CreatePaymentRefundRequest request,
            String correlationId) {
        authenticator.requireAuthenticated(internalToken);
        requireAvailableAndValid(paymentIntentId, idempotencyKey, request);
        String safeCorrelation = correlationId == null || correlationId.isBlank()
                ? ids.next() : correlationId;

        RefundRecord existing = repository.findByKey(idempotencyKey).orElse(null);
        if (existing != null) {
            validateReplay(existing, paymentIntentId, request);
            return settle(existing, safeCorrelation);
        }

        RefundRecord reserved = transactions.execute(status -> reserve(
                paymentIntentId, idempotencyKey, request, safeCorrelation, clock.instant()));
        if (reserved == null) {
            throw new IllegalStateException("Refund reservation returned no result.");
        }
        return settle(reserved, safeCorrelation);
    }

    PaymentRefundResponse reconcile(RefundRecord record, String correlationId) {
        return settle(record, correlationId);
    }

    private RefundRecord reserve(
            String paymentIntentId, String idempotencyKey, CreatePaymentRefundRequest request,
            String correlationId, Instant now) {
        var intent = repository.lockIntent(paymentIntentId).orElseThrow(() -> error(
                HttpStatus.NOT_FOUND, "PAYMENT_INTENT_NOT_FOUND", "Payment intent was not found."));
        RefundRecord existing = repository.findByIntent(paymentIntentId).orElse(null);
        if (existing != null) {
            validateReplay(existing, paymentIntentId, request);
            return existing;
        }
        if (!"SUCCEEDED".equals(intent.status())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_STATE_CONFLICT",
                    "Only a succeeded payment can be refunded.");
        }
        if (intent.providerReference() == null || !providers.containsKey(intent.provider())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_PROVIDER_UNSUPPORTED",
                    "The payment provider does not support this refund.");
        }
        if (repository.reservedAmount(paymentIntentId).signum() != 0) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_AMOUNT_CONFLICT",
                    "The payment already has reserved or completed refunds.");
        }
        String refundId = ids.next();
        repository.reserve(refundId, intent, request.cancellationRequestId(), request.orderId(),
                idempotencyKey, correlationId, ids.next(), now);
        return repository.lock(refundId).orElseThrow();
    }

    private PaymentRefundResponse settle(RefundRecord record, String correlationId) {
        if ("SUCCEEDED".equals(record.status()) || "FAILED".equals(record.status())) {
            return response(record);
        }
        PaymentProvider provider = provider(record.provider());
        PaymentRefundResult result;
        try {
            if (record.providerReference() == null) {
                String providerPaymentReference = repository.lockIntent(record.paymentIntentId())
                        .orElseThrow().providerReference();
                result = provider.refund(new PaymentRefundCommand(
                        record.id(), record.paymentIntentId(), providerPaymentReference,
                        record.orderId(), record.cancellationRequestId(),
                        record.amount(), record.currency()));
            } else {
                result = provider.retrieveRefund(record.providerReference());
            }
        } catch (RuntimeException exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_REFUND_PROVIDER_UNAVAILABLE",
                    "Payment refund is temporarily unavailable.");
        }
        Instant now = clock.instant();
        String safeFailure = "FAILED".equals(result.status()) ? "PROVIDER_REFUND_FAILED" : null;
        String payload = refundPayload(record, result, now);
        transactions.executeWithoutResult(status -> repository.applyProviderResult(
                record.id(), result.status(), result.providerReference(), safeFailure,
                record.providerReference() == null ? "FULL_REFUND" : "RETRIEVE_REFUND",
                correlationId, ids.next(), ids.next(), ids.next(), payload, now,
                now.plus(15, ChronoUnit.SECONDS)));
        return response(repository.lock(record.id()).orElseThrow());
    }

    private String refundPayload(RefundRecord record, PaymentRefundResult result, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", record.id());
        payload.put("paymentIntentId", record.paymentIntentId());
        payload.put("orderId", record.orderId());
        payload.put("cancellationRequestId", record.cancellationRequestId());
        payload.put("amount", record.amount());
        payload.put("currency", record.currency());
        payload.put("status", result.status());
        payload.put("occurredAt", now);
        return json(payload);
    }

    private void validateReplay(
            RefundRecord record, String paymentIntentId, CreatePaymentRefundRequest request) {
        if (!record.paymentIntentId().equals(paymentIntentId)
                || !record.orderId().equals(request.orderId())
                || !record.cancellationRequestId().equals(request.cancellationRequestId())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_IDEMPOTENCY_CONFLICT",
                    "The refund command conflicts with an existing refund.");
        }
    }

    private PaymentRefundResponse response(RefundRecord record) {
        boolean fake = DeterministicFakePaymentProvider.PROVIDER.equals(record.provider());
        return new PaymentRefundResponse(
                record.id(), record.paymentIntentId(), record.orderId(),
                record.cancellationRequestId(), record.amount(), record.currency(),
                record.provider(), record.providerReference(), record.status(),
                record.completedAt(), fake ? "Local demo refund" : "Stripe test refund",
                fake ? "No real money is moved." : "Stripe test mode; no production charge occurred.");
    }

    private PaymentProvider provider(String providerName) {
        PaymentProvider provider = providers.get(providerName);
        if (provider == null) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_PROVIDER_UNSUPPORTED",
                    "The payment provider is not enabled in this runtime.");
        }
        return provider;
    }

    private void requireAvailableAndValid(
            String paymentIntentId, String idempotencyKey, CreatePaymentRefundRequest request) {
        if (!properties.enabled()) {
            throw error(HttpStatus.NOT_FOUND, "PAYMENT_REFUND_NOT_AVAILABLE",
                    "Payment refunds are not available.");
        }
        requireUlid(paymentIntentId);
        if (request == null) {
            throw error(HttpStatus.BAD_REQUEST, "PAYMENT_REFUND_INVALID", "A refund request is required.");
        }
        requireUlid(request.orderId());
        requireUlid(request.cancellationRequestId());
        if (idempotencyKey == null || !KEY.matcher(idempotencyKey).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "PAYMENT_REFUND_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.");
        }
    }

    private void requireUlid(String value) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw error(HttpStatus.BAD_REQUEST, "PAYMENT_REFUND_INVALID", "A refund identifier is invalid.");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment refund event serialization failed.", exception);
        }
    }

    private PaymentIntentException error(HttpStatus status, String code, String message) {
        return new PaymentIntentException(status, code, message);
    }
}
