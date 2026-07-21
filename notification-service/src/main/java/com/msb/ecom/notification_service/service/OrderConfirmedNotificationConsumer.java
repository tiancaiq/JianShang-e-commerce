package com.msb.ecom.notification_service.service;

import com.msb.ecom.notification_service.config.NotificationConsumerProperties;
import com.msb.ecom.notification_service.model.NotificationConsumeResult;
import com.msb.ecom.notification_service.model.NotificationPoisonException;
import com.msb.ecom.notification_service.model.NotificationSourceEvent;
import com.msb.ecom.notification_service.model.NotificationSourceRecord;
import com.msb.ecom.notification_service.model.OrderConfirmedNotification;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static com.msb.ecom.notification_service.model.NotificationConsumeResult.Outcome;

@Service
public class OrderConfirmedNotificationConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(OrderConfirmedNotificationConsumer.class);

    private final NotificationConsumerProperties properties;
    private final OrderConfirmedEventParser parser;
    private final NotificationRepository repository;
    private final NotificationUlidGenerator ids;
    private final NotificationMetrics metrics;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public OrderConfirmedNotificationConsumer(
            NotificationConsumerProperties properties,
            OrderConfirmedEventParser parser,
            NotificationRepository repository,
            NotificationUlidGenerator ids,
            NotificationMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this(properties, parser, repository, ids, metrics, transactionManager, Clock.systemUTC());
    }

    OrderConfirmedNotificationConsumer(
            NotificationConsumerProperties properties,
            OrderConfirmedEventParser parser,
            NotificationRepository repository,
            NotificationUlidGenerator ids,
            NotificationMetrics metrics,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.properties = properties;
        this.parser = parser;
        this.repository = repository;
        this.ids = ids;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Projects one direct/fake event into durable in-app state without any live transport.
    public NotificationConsumeResult consume(String rawEvent) {
        if (!properties.enabled()) {
            return result(Outcome.DISABLED, "NOTIFICATION_CONSUMER_DISABLED", null);
        }

        NotificationSourceEvent event;
        try {
            event = parser.parseEnvelope(rawEvent);
        } catch (NotificationPoisonException exception) {
            return result(Outcome.POISON, "NOTIFICATION_EVENT_MALFORMED", null);
        }
        String payloadHash = parser.canonicalHash(event);
        Instant now = clock.instant();
        try {
            return Objects.requireNonNull(transactions.execute(status ->
                    process(event, payloadHash, now)));
        } catch (DuplicateKeyException exception) {
            return replayAfterRace(event, payloadHash);
        } catch (TransientDataAccessException exception) {
            return replayAfterRace(event, payloadHash);
        } catch (DataAccessException exception) {
            return result(
                    Outcome.RETRY_REQUIRED,
                    "NOTIFICATION_PERSISTENCE_RETRY_REQUIRED",
                    event.correlationId());
        }
    }

    private NotificationConsumeResult process(
            NotificationSourceEvent event,
            String payloadHash,
            Instant now) {
        NotificationSourceRecord existing = repository
                .lockSource(properties.consumerName(), event.eventId())
                .orElse(null);
        if (existing != null) {
            return replay(existing, payloadHash, event.correlationId());
        }

        Instant retentionUntil = now.plus(properties.retention());
        repository.insertProcessing(
                properties.consumerName(), event, payloadHash, retentionUntil, now);
        if (!event.isSupportedOrderConfirmed()) {
            repository.complete(
                    properties.consumerName(),
                    event.eventId(),
                    "REJECTED",
                    "NOTIFICATION_EVENT_UNSUPPORTED",
                    now);
            return result(
                    Outcome.REJECTED,
                    "NOTIFICATION_EVENT_UNSUPPORTED",
                    event.correlationId());
        }

        OrderConfirmedNotification notification;
        try {
            notification = parser.parseSupportedPayload(event);
        } catch (NotificationPoisonException exception) {
            repository.complete(
                    properties.consumerName(),
                    event.eventId(),
                    "POISON",
                    "NOTIFICATION_EVENT_MALFORMED",
                    now);
            return result(
                    Outcome.POISON,
                    "NOTIFICATION_EVENT_MALFORMED",
                    event.correlationId());
        }

        repository.insertOrderConfirmed(
                ids.next(),
                properties.consumerName(),
                event,
                notification,
                payloadHash,
                retentionUntil,
                now);
        repository.complete(
                properties.consumerName(), event.eventId(), "CREATED", null, now);
        return result(Outcome.CREATED, null, event.correlationId());
    }

    private NotificationConsumeResult replayAfterRace(
            NotificationSourceEvent event,
            String payloadHash) {
        try {
            NotificationSourceRecord winner = repository
                    .findSource(properties.consumerName(), event.eventId())
                    .orElse(null);
            if (winner == null) {
                return result(
                        Outcome.RETRY_REQUIRED,
                        "NOTIFICATION_PERSISTENCE_RETRY_REQUIRED",
                        event.correlationId());
            }
            return replay(winner, payloadHash, event.correlationId());
        } catch (DataAccessException exception) {
            return result(
                    Outcome.RETRY_REQUIRED,
                    "NOTIFICATION_PERSISTENCE_RETRY_REQUIRED",
                    event.correlationId());
        }
    }

    private NotificationConsumeResult replay(
            NotificationSourceRecord existing,
            String payloadHash,
            String correlationId) {
        if (!MessageDigest.isEqual(
                existing.payloadHash().getBytes(StandardCharsets.US_ASCII),
                payloadHash.getBytes(StandardCharsets.US_ASCII))) {
            return result(
                    Outcome.CONFLICT,
                    "NOTIFICATION_SOURCE_EVENT_CONFLICT",
                    correlationId);
        }
        if ("COMPLETED".equals(existing.state())) {
            return result(Outcome.REPLAYED, null, correlationId);
        }
        if ("REJECTED".equals(existing.state())) {
            Outcome outcome = "POISON".equals(existing.outcome())
                    ? Outcome.POISON
                    : Outcome.REJECTED;
            return result(outcome, existing.safeErrorCode(), correlationId);
        }
        return result(
                Outcome.RETRY_REQUIRED,
                "NOTIFICATION_PERSISTENCE_RETRY_REQUIRED",
                correlationId);
    }

    private NotificationConsumeResult result(
            Outcome outcome,
            String safeCode,
            String correlationId) {
        if (outcome != Outcome.DISABLED) {
            metrics.consumed(outcome.name());
            log.info(
                    "Order-confirmed notification result={} code={} correlationId={}",
                    outcome,
                    safeCode == null ? "NONE" : safeCode,
                    correlationId == null ? "NONE" : correlationId);
        }
        return new NotificationConsumeResult(outcome, safeCode);
    }
}
