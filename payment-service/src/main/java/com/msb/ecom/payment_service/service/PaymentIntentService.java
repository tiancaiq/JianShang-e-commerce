package com.msb.ecom.payment_service.service;

import com.msb.ecom.payment_service.dto.CreatePaymentIntentRequest;
import com.msb.ecom.payment_service.dto.PaymentIntentResponse;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentProviderCommand;
import com.msb.ecom.payment_service.provider.PaymentProviderResult;
import com.msb.ecom.payment_service.repository.PaymentIdempotencyRecord;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PaymentIntentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentService.class);
    private static final String CALLER_SCOPE = "ORDER_SERVICE";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private final PaymentIntentRepository repository;
    private final PaymentProvider provider;
    private final InternalPaymentAuthenticator authenticator;
    private final PaymentUlidGenerator ids;
    private final TransactionTemplate transactions;
    private final boolean enabled;

    public PaymentIntentService(
            PaymentIntentRepository repository,
            PaymentProvider provider,
            InternalPaymentAuthenticator authenticator,
            PaymentUlidGenerator ids,
            TransactionTemplate transactions,
            @Value("${payment.intents.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.provider = provider;
        this.authenticator = authenticator;
        this.ids = ids;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    // Creates or replays one provider intent for the authoritative checkout snapshot.
    public PaymentIntentResponse create(
            String internalToken,
            String idempotencyKey,
            CreatePaymentIntentRequest request,
            String correlationId) {
        requireAvailable(internalToken);
        validateIdempotencyKey(idempotencyKey);
        List<String> businessIds = normalizedBusinessIds(request.businessIds());
        String requestHash = requestHash(request, businessIds);

        PaymentIdempotencyRecord existing = repository.idempotency(CALLER_SCOPE, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            return replay(existing, requestHash, idempotencyKey, correlationId);
        }

        Instant now = Instant.now();
        String paymentIntentId = ids.next();
        PaymentIntent created = new PaymentIntent(
                paymentIntentId,
                request.checkoutId(),
                request.checkoutVersion(),
                request.checkoutSnapshotHash(),
                request.buyerId(),
                CALLER_SCOPE,
                businessIds,
                request.amount(),
                request.currency(),
                "CARD",
                "AUTOMATIC",
                "PLATFORM",
                "SEPARATE_CHARGE_TRANSFER",
                provider.providerName(),
                null,
                null,
                PaymentIntentStatus.CREATED,
                0,
                request.expiresAt(),
                null,
                null,
                now,
                now);

        try {
            transactions.executeWithoutResult(status -> {
                repository.findByCheckout(request.checkoutId()).ifPresent(intent -> {
                    throw conflict(
                            "PAYMENT_INTENT_ALREADY_EXISTS",
                            "A payment intent already exists for this checkout.");
                });
                repository.insert(created, ids.next(), correlationId);
                repository.insertIdempotency(
                        ids.next(),
                        CALLER_SCOPE,
                        idempotencyKey,
                        requestHash,
                        paymentIntentId,
                        now,
                        request.expiresAt().plus(24, ChronoUnit.HOURS));
            });
        } catch (DataIntegrityViolationException exception) {
            PaymentIdempotencyRecord raced = repository.idempotency(CALLER_SCOPE, idempotencyKey)
                    .orElse(null);
            if (raced != null) {
                return replay(raced, requestHash, idempotencyKey, correlationId);
            }
            throw conflict(
                    "PAYMENT_INTENT_ALREADY_EXISTS",
                    "A payment intent already exists for this checkout.");
        }
        return provision(created, idempotencyKey, correlationId);
    }

    // Returns an intent only when the internal caller supplies its persisted buyer scope.
    public PaymentIntentResponse get(
            String internalToken,
            String paymentIntentId,
            String buyerId) {
        requireAvailable(internalToken);
        PaymentIntent intent = repository.findOwned(paymentIntentId, buyerId)
                .orElseThrow(() -> new PaymentIntentException(
                        HttpStatus.NOT_FOUND,
                        "PAYMENT_INTENT_NOT_FOUND",
                        "Payment intent was not found."));
        return response(intent);
    }

    private PaymentIntentResponse replay(
            PaymentIdempotencyRecord record,
            String requestHash,
            String idempotencyKey,
            String correlationId) {
        if (!MessageDigest.isEqual(
                record.requestHash().getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw conflict(
                    "PAYMENT_IDEMPOTENCY_CONFLICT",
                    "Idempotency key was already used with a different request.");
        }
        PaymentIntent intent = repository.find(record.paymentIntentId())
                .orElseThrow(() -> new PaymentIntentException(
                        HttpStatus.CONFLICT,
                        "PAYMENT_IDEMPOTENCY_INCOMPLETE",
                        "Payment intent replay is not ready."));
        if (intent.status() == PaymentIntentStatus.CREATED) {
            return provision(intent, idempotencyKey, correlationId);
        }
        repository.completeIdempotency(CALLER_SCOPE, idempotencyKey, Instant.now());
        return response(intent);
    }

    private PaymentIntentResponse provision(
            PaymentIntent created,
            String idempotencyKey,
            String correlationId) {
        PaymentProviderResult providerResult;
        try {
            providerResult = provider.createIntent(new PaymentProviderCommand(
                    created.id(),
                    "payment:create:" + created.id() + ":v1",
                    created.checkoutId(),
                    created.amount(),
                    created.currency(),
                    created.paymentMethodType(),
                    created.captureMethod(),
                    created.merchantOfRecord(),
                    created.fundsFlow(),
                    created.businessIds()));
        } catch (RuntimeException exception) {
            log.warn(
                    "Payment provider adapter failed paymentIntentId={} correlationId={}",
                    created.id(),
                    correlationId);
            throw new PaymentIntentException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_PROVIDER_UNAVAILABLE",
                    "Payment provider is temporarily unavailable.");
        }
        PaymentProviderResult result = providerResult;
        Instant now = Instant.now();
        transactions.executeWithoutResult(status -> {
            PaymentIntent current = repository.find(created.id())
                    .orElseThrow(() -> new PaymentIntentException(
                            HttpStatus.CONFLICT,
                            "PAYMENT_INTENT_NOT_FOUND",
                            "Payment intent was not found."));
            if (current.status() == PaymentIntentStatus.CREATED) {
                PaymentIntent next = current.transition(
                        result.status(),
                        result.providerReference(),
                        result.actionType(),
                        result.safeErrorCode(),
                        result.safeErrorMessage(),
                        now);
                int changed = repository.transition(
                        current,
                        next,
                        ids.next(),
                        result.status() == PaymentIntentStatus.FAILED
                                ? "PROVIDER_CREATE_FAILED"
                                : "PROVIDER_INTENT_CREATED",
                        correlationId);
                if (changed == 1) {
                    repository.insertAttempt(
                            ids.next(),
                            current.id(),
                            repository.nextAttemptNumber(current.id()),
                            current.provider(),
                            result.status().name(),
                            result.providerReference(),
                            result.safeErrorCode(),
                            result.safeErrorMessage(),
                            now);
                }
            }
            repository.completeIdempotency(CALLER_SCOPE, idempotencyKey, now);
        });
        return response(repository.find(created.id()).orElseThrow());
    }

    private PaymentIntentResponse response(PaymentIntent intent) {
        PaymentIntentResponse.ProviderAction action = intent.providerActionType() == null
                ? null
                : new PaymentIntentResponse.ProviderAction(
                        intent.providerActionType(),
                        provider.actionReference(intent.id(), intent.providerReference()),
                        provider.publicClientKey(),
                        provider.returnUrl(intent.checkoutId()));
        PaymentIntentResponse.SafeProviderError error = intent.safeErrorCode() == null
                ? null
                : new PaymentIntentResponse.SafeProviderError(
                        intent.safeErrorCode(),
                        intent.safeErrorMessage());
        return new PaymentIntentResponse(
                intent.id(),
                intent.checkoutId(),
                intent.checkoutVersion(),
                intent.buyerId(),
                intent.businessIds(),
                intent.amount(),
                intent.currency(),
                intent.provider(),
                intent.providerReference(),
                intent.status().name(),
                intent.version(),
                intent.expiresAt(),
                action,
                error,
                intent.createdAt(),
                intent.updatedAt());
    }

    private void requireAvailable(String internalToken) {
        if (!enabled) {
            throw new PaymentIntentException(
                    HttpStatus.NOT_FOUND,
                    "PAYMENT_INTENTS_DISABLED",
                    "Payment intents are not available.");
        }
        authenticator.requireAuthenticated(internalToken);
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new PaymentIntentException(
                    HttpStatus.BAD_REQUEST,
                    "PAYMENT_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.");
        }
    }

    private List<String> normalizedBusinessIds(List<String> supplied) {
        List<String> normalized = new ArrayList<>(supplied);
        normalized.sort(String::compareTo);
        if (normalized.stream().distinct().count() != normalized.size()) {
            throw new PaymentIntentException(
                    HttpStatus.BAD_REQUEST,
                    "PAYMENT_BUSINESS_SCOPE_INVALID",
                    "Business scopes must be unique.");
        }
        return List.copyOf(normalized);
    }

    private String requestHash(CreatePaymentIntentRequest request, List<String> businessIds) {
        BigDecimal amount = request.amount().setScale(4);
        String canonical = String.join("|",
                CALLER_SCOPE,
                request.checkoutId(),
                Long.toString(request.checkoutVersion()),
                request.checkoutSnapshotHash(),
                request.buyerId(),
                String.join(",", businessIds),
                amount.toPlainString(),
                request.currency(),
                request.expiresAt().toString());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private PaymentIntentException conflict(String code, String message) {
        return new PaymentIntentException(HttpStatus.CONFLICT, code, message);
    }
}
