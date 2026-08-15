package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.config.StripeReconciliationProperties;
import com.msb.ecom.payment_service.model.PaymentIntent;
import com.msb.ecom.payment_service.model.PaymentIntentStatus;
import com.msb.ecom.payment_service.provider.PaymentProvider;
import com.msb.ecom.payment_service.provider.PaymentProviderResult;
import com.msb.ecom.payment_service.provider.StripeTestPaymentProvider;
import com.msb.ecom.payment_service.repository.PaymentIntentRepository;
import com.msb.ecom.payment_service.repository.PaymentRefundRepository;
import com.msb.ecom.payment_service.repository.PaymentReturnRefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class StripeReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(StripeReconciliationWorker.class);
    private final StripeReconciliationProperties properties;
    private final PaymentIntentRepository intents;
    private final PaymentRefundRepository refunds;
    private final PaymentReturnRefundRepository returnRefunds;
    private final PaymentRefundService refundService;
    private final PaymentReturnRefundService returnRefundService;
    private final PaymentProvider stripe;
    private final PaymentUlidGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    public StripeReconciliationWorker(
            StripeReconciliationProperties properties,
            PaymentIntentRepository intents,
            PaymentRefundRepository refunds,
            PaymentReturnRefundRepository returnRefunds,
            PaymentRefundService refundService,
            PaymentReturnRefundService returnRefundService,
            List<PaymentProvider> providers,
            PaymentUlidGenerator ids,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.intents = intents;
        this.refunds = refunds;
        this.returnRefunds = returnRefunds;
        this.refundService = refundService;
        this.returnRefundService = returnRefundService;
        this.stripe = providers.stream()
                .filter(provider -> StripeTestPaymentProvider.PROVIDER.equals(provider.providerName()))
                .findFirst().orElse(null);
        this.ids = ids;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${payment.stripe.reconciliation-interval-ms:5000}")
    public void reconcile() {
        if (!properties.enabled() || stripe == null) {
            return;
        }
        Instant now = clock.instant();
        reconcilePayments(now);
        refunds.due(now, properties.batchSize()).forEach(record -> {
            try {
                refundService.reconcile(record, "stripe-reconcile:" + record.id());
            } catch (RuntimeException exception) {
                log.warn("Stripe refund reconciliation deferred refundId={} category={}",
                        record.id(), exception.getClass().getSimpleName());
            }
        });
        returnRefunds.due(now, properties.batchSize()).forEach(record -> {
            try {
                returnRefundService.reconcile(record, "stripe-reconcile:" + record.id());
            } catch (RuntimeException exception) {
                log.warn("Stripe return refund reconciliation deferred refundId={} category={}",
                        record.id(), exception.getClass().getSimpleName());
            }
        });
    }

    private void reconcilePayments(Instant now) {
        Instant cutoff = now.minus(properties.minimumAge());
        for (PaymentIntent candidate : intents.reconciliationCandidates(
                StripeTestPaymentProvider.PROVIDER, cutoff, properties.batchSize())) {
            try {
                PaymentProviderResult result = stripe.retrieveIntent(candidate.providerReference());
                if (result.status() == candidate.status()
                        || result.status() == PaymentIntentStatus.REQUIRES_ACTION) {
                    continue;
                }
                transactions.executeWithoutResult(status -> applyPayment(candidate, result, now));
            } catch (RuntimeException exception) {
                log.warn("Stripe payment reconciliation deferred paymentIntentId={} category={}",
                        candidate.id(), exception.getClass().getSimpleName());
            }
        }
    }

    // Applies retrieved provider state through the same legal internal transitions and outbox boundary.
    private void applyPayment(PaymentIntent candidate, PaymentProviderResult result, Instant now) {
        PaymentIntent current = intents.findByProviderReferenceForUpdate(
                StripeTestPaymentProvider.PROVIDER, candidate.providerReference()).orElse(null);
        if (current == null || current.status() == result.status()
                || current.status() == PaymentIntentStatus.SUCCEEDED
                || current.status() == PaymentIntentStatus.FAILED) {
            return;
        }
        PaymentIntent next = current.transition(
                result.status(), current.providerReference(), result.actionType(),
                result.safeErrorCode(), result.safeErrorMessage(), now);
        if (intents.transitionAs(current, next, ids.next(), "PROVIDER_RECONCILED",
                "STRIPE_RECONCILIATION", "stripe-reconcile:" + current.id()) != 1) {
            return;
        }
        intents.insertReconciliationAttempt(
                ids.next(), current.id(), intents.nextAttemptNumber(current.id()),
                current.provider(), result.status().name(), current.providerReference(),
                result.safeErrorCode(), result.safeErrorMessage(), now);
        if (result.status() == PaymentIntentStatus.SUCCEEDED
                || result.status() == PaymentIntentStatus.FAILED) {
            String eventId = "reconcile_" + current.id();
            intents.insertOutbox(ids.next(), current.id(),
                    result.status() == PaymentIntentStatus.SUCCEEDED
                            ? "payment.succeeded" : "payment.failed",
                    outboxPayload(current, result.status(), eventId),
                    "stripe-reconcile:" + current.id(), eventId, now, now);
        }
    }

    private String outboxPayload(PaymentIntent intent, PaymentIntentStatus status, String eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentIntentId", intent.id());
        payload.put("checkoutId", intent.checkoutId());
        payload.put("status", status.name());
        payload.put("amount", intent.amount().toPlainString());
        payload.put("currency", intent.currency());
        payload.put("providerEventId", eventId);
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment reconciliation payload serialization failed.", exception);
        }
    }
}
