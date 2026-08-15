package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.config.PaymentRefundProperties;
import com.msb.ecom.payment_service.dto.CreateReturnRefundRequest;
import com.msb.ecom.payment_service.dto.ReturnRefundResponse;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.provider.DeterministicFakePaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentRefundCommand;
import com.msb.ecom.payment_service.provider.PaymentRefundResult;
import com.msb.ecom.payment_service.repository.PaymentRefundRepository;
import com.msb.ecom.payment_service.repository.PaymentReturnRefundRepository;
import com.msb.ecom.payment_service.repository.PaymentReturnRefundRepository.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentReturnRefundService {

    private final PaymentRefundProperties properties;
    private final InternalPaymentAuthenticator authenticator;
    private final PaymentRefundRepository intents;
    private final PaymentReturnRefundRepository refunds;
    private final Map<String, PaymentProvider> providers;
    private final PaymentUlidGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    public PaymentReturnRefundService(
            PaymentRefundProperties properties, InternalPaymentAuthenticator authenticator,
            PaymentRefundRepository intents, PaymentReturnRefundRepository refunds,
            List<PaymentProvider> providers, PaymentUlidGenerator ids, ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.authenticator = authenticator;
        this.intents = intents;
        this.refunds = refunds;
        this.providers = providers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                PaymentProvider::providerName, provider -> provider));
        this.ids = ids;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // Reserves the immutable business-group amount under the locked succeeded payment boundary.
    public ReturnRefundResponse refund(
            String token, String intentId, String key, CreateReturnRefundRequest request,
            String correlationId) {
        authenticator.requireAuthenticated(token);
        validate(request, key);
        String correlation = correlationId == null || correlationId.isBlank() ? ids.next() : correlationId;
        Record existing = refunds.findByKey(key).orElse(null);
        if (existing != null) {
            validateReplay(existing, intentId, request);
            return settle(existing, correlation);
        }
        Record reserved = transactions.execute(status -> reserve(
                intentId, key, request, correlation, clock.instant()));
        if (reserved == null) {
            throw new IllegalStateException("Return refund reservation returned no result.");
        }
        return settle(reserved, correlation);
    }

    ReturnRefundResponse reconcile(Record record, String correlationId) {
        return settle(record, correlationId);
    }

    private Record reserve(
            String intentId, String key, CreateReturnRefundRequest request,
            String correlation, Instant now) {
        Record existing = refunds.findByReturn(request.returnId()).orElse(null);
        if (existing != null) {
            validateReplay(existing, intentId, request);
            return existing;
        }
        var intent = intents.lockIntent(intentId).orElseThrow(() -> error(
                HttpStatus.NOT_FOUND, "PAYMENT_INTENT_NOT_FOUND", "Payment intent was not found."));
        if (!"SUCCEEDED".equals(intent.status())
                || !request.currency().equals(intent.currency())
                || request.amount().compareTo(intent.amount()) > 0
                || intents.reservedAmount(intentId).add(request.amount()).compareTo(intent.amount()) > 0) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_RETURN_REFUND_STATE_CONFLICT",
                    "Return refund exceeds the succeeded payment boundary.");
        }
        if (intent.providerReference() == null || !providers.containsKey(intent.provider())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_PROVIDER_UNSUPPORTED",
                    "The payment provider does not support this refund.");
        }
        String refundId = ids.next();
        refunds.reserve(refundId, intentId, request.returnId(), request.orderId(),
                request.businessOrderId(), key, request.amount(), request.currency(),
                intent.provider(), correlation, now);
        return refunds.lock(refundId).orElseThrow();
    }

    private ReturnRefundResponse settle(Record record, String correlation) {
        if ("SUCCEEDED".equals(record.status()) || "FAILED".equals(record.status())) {
            return response(record);
        }
        PaymentProvider provider = providers.get(record.provider());
        if (provider == null) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_REFUND_PROVIDER_UNSUPPORTED",
                    "The payment provider is not enabled in this runtime.");
        }
        PaymentRefundResult result;
        try {
            if (record.providerReference() == null) {
                String paymentReference = intents.lockIntent(record.paymentIntentId())
                        .orElseThrow().providerReference();
                result = provider.refund(new PaymentRefundCommand(
                        record.id(), record.paymentIntentId(), paymentReference, record.orderId(),
                        record.returnId(), record.amount(), record.currency()));
            } else {
                result = provider.retrieveRefund(record.providerReference());
            }
        } catch (RuntimeException exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_REFUND_PROVIDER_UNAVAILABLE",
                    "Payment refund is temporarily unavailable.");
        }
        Instant now = clock.instant();
        String failure = "FAILED".equals(result.status()) ? "PROVIDER_REFUND_FAILED" : null;
        transactions.executeWithoutResult(status -> refunds.applyProviderResult(
                record.id(), result.status(), result.providerReference(), failure,
                record.providerReference() == null ? "PARTIAL_REFUND" : "RETRIEVE_REFUND",
                correlation, ids.next(), ids.next(), payload(record, result, now), now,
                now.plus(15, ChronoUnit.SECONDS)));
        return response(refunds.lock(record.id()).orElseThrow());
    }

    private String payload(Record record, PaymentRefundResult result, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", record.id());
        payload.put("returnId", record.returnId());
        payload.put("orderId", record.orderId());
        payload.put("businessOrderId", record.businessOrderId());
        payload.put("amount", record.amount());
        payload.put("currency", record.currency());
        payload.put("status", result.status());
        payload.put("occurredAt", now);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Return refund event serialization failed.", exception);
        }
    }

    private void validate(CreateReturnRefundRequest request, String key) {
        if (!properties.enabled()) {
            throw error(HttpStatus.NOT_FOUND, "PAYMENT_REFUND_NOT_AVAILABLE",
                    "Payment refunds are unavailable.");
        }
        if (request == null || blank(request.returnId()) || blank(request.orderId())
                || blank(request.businessOrderId()) || request.amount() == null
                || request.amount().signum() <= 0 || !"USD".equals(request.currency())
                || blank(key)) {
            throw error(HttpStatus.BAD_REQUEST, "PAYMENT_RETURN_REFUND_INVALID",
                    "A complete return refund command is required.");
        }
    }

    private void validateReplay(Record record, String intentId, CreateReturnRefundRequest request) {
        if (!record.paymentIntentId().equals(intentId)
                || !record.returnId().equals(request.returnId())
                || !record.orderId().equals(request.orderId())
                || !record.businessOrderId().equals(request.businessOrderId())
                || record.amount().compareTo(request.amount()) != 0
                || !record.currency().equals(request.currency())) {
            throw error(HttpStatus.CONFLICT, "PAYMENT_RETURN_REFUND_IDEMPOTENCY_CONFLICT",
                    "Return refund conflicts with an existing command.");
        }
    }

    private ReturnRefundResponse response(Record record) {
        boolean fake = DeterministicFakePaymentProvider.PROVIDER.equals(record.provider());
        return new ReturnRefundResponse(
                record.id(), record.returnId(), record.orderId(), record.businessOrderId(),
                record.amount(), record.currency(), record.status(), record.completedAt(),
                fake ? "Local demo refund" : "Stripe test refund",
                fake ? "No real money is moved." : "Stripe test mode; no production charge occurred.");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private PaymentIntentException error(HttpStatus status, String code, String message) {
        return new PaymentIntentException(status, code, message);
    }
}
