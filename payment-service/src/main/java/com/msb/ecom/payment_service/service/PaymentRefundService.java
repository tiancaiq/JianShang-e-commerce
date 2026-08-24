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
import com.msb.ecom.payment_service.repository.PaymentRefundRepository;
import com.msb.ecom.payment_service.repository.PaymentRefundRepository.RefundRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
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

    // Creates exactly one full refund derived from the authoritative succeeded intent.
    public PaymentRefundResponse refund(
            String internalToken,
            String paymentIntentId,
            String idempotencyKey,
            CreatePaymentRefundRequest request,
            String correlationId) {
        authenticator.requireAuthenticated(internalToken);
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
        RefundRecord keyed = repository.findByKey(idempotencyKey).orElse(null);
        if (keyed != null) {
            return replay(keyed, paymentIntentId, request);
        }
        String safeCorrelation = correlationId == null || correlationId.isBlank()
                ? ids.next() : correlationId;
        return transactions.execute(status -> execute(
                paymentIntentId, idempotencyKey, request, safeCorrelation, clock.instant()));
    }

    private PaymentRefundResponse execute(
            String paymentIntentId,
            String idempotencyKey,
            CreatePaymentRefundRequest request,
            String correlationId,
            Instant now) {
        var intent = repository.lockIntent(paymentIntentId).orElseThrow(() -> error(
                HttpStatus.NOT_FOUND, "PAYMENT_INTENT_NOT_FOUND", "Payment intent was not found."));
        RefundRecord existing = repository.findByIntent(paymentIntentId).orElse(null);
        if (existing != null) {
            return replay(existing, paymentIntentId, request);
        }
        if (!"SUCCEEDED".equals(intent.status())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_STATE_CONFLICT",
                    "Only a succeeded payment can be refunded.");
        }
        if (repository.reservedAmount(paymentIntentId).signum() > 0) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_STATE_CONFLICT",
                    "A cancellation refund cannot exceed the payment's remaining refundable amount.");
        }
        if (!DeterministicFakePaymentProvider.PROVIDER.equals(intent.provider())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_PROVIDER_UNSUPPORTED",
                    "The payment provider does not support this bounded refund.");
        }
        PaymentProvider provider = providers.get(intent.provider());
        if (provider == null) {
            throw new IllegalStateException("Configured payment provider is unavailable.");
        }
        String refundId = ids.next();
        var result = provider.refund(new PaymentRefundCommand(
                refundId, paymentIntentId, request.orderId(), request.cancellationRequestId(),
                intent.amount(), intent.currency()));
        if (!"SUCCEEDED".equals(result.status()) || result.providerReference() == null) {
            throw new IllegalStateException("Local demo refund did not succeed deterministically.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", refundId);
        payload.put("paymentIntentId", paymentIntentId);
        payload.put("orderId", request.orderId());
        payload.put("cancellationRequestId", request.cancellationRequestId());
        payload.put("amount", intent.amount());
        payload.put("currency", intent.currency());
        payload.put("status", "SUCCEEDED");
        payload.put("occurredAt", now);
        repository.insert(refundId, paymentIntentId, request.cancellationRequestId(),
                request.orderId(), idempotencyKey, intent.amount(), intent.currency(),
                intent.provider(), result.providerReference(), correlationId,
                ids.next(), ids.next(), ids.next(), json(payload), now);
        return response(new RefundRecord(refundId, paymentIntentId,
                request.cancellationRequestId(), request.orderId(), idempotencyKey,
                intent.amount(), intent.currency(), intent.provider(), result.providerReference(),
                "SUCCEEDED", now));
    }

    private PaymentRefundResponse replay(
            RefundRecord record,
            String paymentIntentId,
            CreatePaymentRefundRequest request) {
        if (!record.paymentIntentId().equals(paymentIntentId)
                || !record.orderId().equals(request.orderId())
                || !record.cancellationRequestId().equals(request.cancellationRequestId())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_IDEMPOTENCY_CONFLICT",
                    "The refund command conflicts with an existing refund.");
        }
        return response(record);
    }

    private PaymentRefundResponse response(RefundRecord record) {
        return new PaymentRefundResponse(
                record.id(), record.paymentIntentId(), record.orderId(),
                record.cancellationRequestId(), record.amount(), record.currency(),
                record.provider(), record.providerReference(), record.status(),
                record.completedAt(), "Local demo refund", "No real money is moved.");
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
