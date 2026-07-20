package com.msb.ecom.payment_service.outbox;

import com.msb.ecom.payment_service.config.PaymentOutboxProperties;
import com.msb.ecom.payment_service.repository.PaymentOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutboxDispatcher.class);
    private static final Set<String> SAFE_ERROR_CODES = Set.of(
            "FAKE_TRANSPORT_FAILURE",
            "OUTBOX_EVENT_INVALID",
            "TRANSPORT_REJECTED",
            "TRANSPORT_TIMEOUT",
            "TRANSPORT_UNAVAILABLE");

    private final PaymentOutboxRepository repository;
    private final PaymentOutboxMessageFactory messageFactory;
    private final PaymentEventTransport transport;
    private final PaymentOutboxProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public PaymentOutboxDispatcher(
            PaymentOutboxRepository repository,
            PaymentOutboxMessageFactory messageFactory,
            PaymentEventTransport transport,
            PaymentOutboxProperties properties,
            PlatformTransactionManager transactionManager) {
        this(
                repository,
                messageFactory,
                transport,
                properties,
                transactionManager,
                Clock.systemUTC());
    }

    PaymentOutboxDispatcher(
            PaymentOutboxRepository repository,
            PaymentOutboxMessageFactory messageFactory,
            PaymentEventTransport transport,
            PaymentOutboxProperties properties,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.messageFactory = messageFactory;
        this.transport = transport;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        if (properties.enabled() && !transport.available()) {
            throw new IllegalStateException(
                    "Payment outbox dispatch requires an available event transport when enabled.");
        }
    }

    // Publishes one bounded claimed batch and marks each row only after transport acknowledgement.
    public int dispatchBatch() {
        if (!properties.enabled()) {
            return 0;
        }
        Instant claimedAt = clock.instant();
        String claimToken = UUID.randomUUID().toString();
        List<PaymentOutboxRecord> records = transactions.execute(status -> repository.claim(
                claimToken,
                claimedAt,
                claimedAt.plus(properties.claimLease()),
                properties.batchSize()));
        if (records == null || records.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (PaymentOutboxRecord record : records) {
            try {
                PaymentEventMessage message = messageFactory.create(record);
                transport.publish(message);
                Instant completedAt = clock.instant();
                Integer changed = transactions.execute(status ->
                        repository.markPublished(record.id(), claimToken, completedAt));
                if (changed != null && changed == 1) {
                    published++;
                }
            } catch (RuntimeException exception) {
                recordFailure(record, claimToken, exception);
            }
        }
        return published;
    }

    private void recordFailure(
            PaymentOutboxRecord record,
            String claimToken,
            RuntimeException exception) {
        int failures = record.retryCount() + 1;
        boolean terminal = failures >= properties.maxAttempts();
        String safeCode = exception instanceof PaymentEventTransportException transportException
                ? boundedCode(transportException.safeCode())
                : "TRANSPORT_FAILURE";
        String safeMessage = terminal
                ? "Payment event delivery exhausted its retry limit."
                : "Payment event delivery will be retried.";
        Instant failedAt = clock.instant();
        Instant nextAttemptAt = failedAt.plus(properties.backoffAfter(record.retryCount()));
        transactions.executeWithoutResult(status -> repository.markFailed(
                record.id(),
                claimToken,
                failedAt,
                nextAttemptAt,
                terminal,
                safeCode,
                safeMessage));
        log.warn(
                "Payment outbox delivery failed eventId={} code={} terminal={}",
                record.id(),
                safeCode,
                terminal);
    }

    private String boundedCode(String candidate) {
        if (!SAFE_ERROR_CODES.contains(candidate)) {
            return "TRANSPORT_FAILURE";
        }
        return candidate;
    }
}
