package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(
        name = "listing.knowledge.publication.publisher-enabled",
        havingValue = "true")
@Slf4j
public class ListingKnowledgeOutboxPublisher {

    private final ListingKnowledgeRepository repository;
    private final ListingKnowledgePublicationProperties properties;
    private final ListingKnowledgePublicationMetrics metrics;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final UlidGenerator ulidGenerator;
    private final CategoryGuidanceMetrics categoryGuidanceMetrics;

    public ListingKnowledgeOutboxPublisher(
            ListingKnowledgeRepository repository,
            ListingKnowledgePublicationProperties properties,
            ListingKnowledgePublicationMetrics metrics,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            UlidGenerator ulidGenerator,
            CategoryGuidanceMetrics categoryGuidanceMetrics) {
        this.repository = repository;
        this.properties = properties;
        this.metrics = metrics;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.ulidGenerator = ulidGenerator;
        this.categoryGuidanceMetrics = categoryGuidanceMetrics;
    }

    @Scheduled(fixedDelayString = "${listing.knowledge.publication.poll-interval-ms:5000}")
    // Claims and publishes a bounded outbox batch; failures remain durable and never affect listing transactions.
    public void publishPending() {
        Instant now = Instant.now();
        String claimToken = ulidGenerator.next();
        List<ListingKnowledgeOutboxEvent> events = repository.claimBatch(
                claimToken,
                now,
                now.plusSeconds(properties.claimSeconds()),
                properties.batchSize());
        for (ListingKnowledgeOutboxEvent event : events) {
            publish(event, claimToken);
        }
    }

    private void publish(ListingKnowledgeOutboxEvent event, String claimToken) {
        try {
            kafkaTemplate.send(event.topic(), event.messageKey(), envelope(event))
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (repository.markPublished(event.eventId(), claimToken, Instant.now())) {
                metrics.published();
                categoryGuidanceMetrics.outboxResult(event.topic(), "success");
                log.info("Published listing knowledge event eventId={} eventType={}",
                        event.eventId(), event.eventType());
            } else {
                metrics.lostClaim();
                log.warn("Listing knowledge event acknowledgement lost claim eventId={}", event.eventId());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFailure(event, claimToken, "INTERRUPTED");
        } catch (TimeoutException exception) {
            recordFailure(event, claimToken, "KAFKA_TIMEOUT");
        } catch (ExecutionException exception) {
            recordFailure(event, claimToken, "KAFKA_SEND_FAILED");
        } catch (RuntimeException exception) {
            recordFailure(event, claimToken, "PUBLISHER_RUNTIME_FAILURE");
        }
    }

    private void recordFailure(
            ListingKnowledgeOutboxEvent event,
            String claimToken,
            String errorCode) {
        Instant nextAttemptAt = Instant.now().plus(retryDelay(event.retryCount()));
        repository.markFailed(event.eventId(), claimToken, nextAttemptAt, errorCode);
        metrics.failed(errorCode);
        categoryGuidanceMetrics.outboxResult(event.topic(), "failed");
        log.warn("Listing knowledge event publish failed eventId={} eventType={} errorCode={}",
                event.eventId(), event.eventType(), errorCode);
    }

    private Duration retryDelay(int retryCount) {
        long multiplier = 1L << Math.min(retryCount, 20);
        Duration candidate;
        try {
            candidate = properties.retryBase().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            candidate = properties.retryMax();
        }
        Duration capped = candidate.compareTo(properties.retryMax()) > 0 ? properties.retryMax() : candidate;
        if (capped.equals(properties.retryMax())) {
            return capped;
        }
        long jitterBound = Math.max(1, capped.toMillis() / 4);
        Duration jittered = capped.plusMillis(ThreadLocalRandom.current().nextLong(jitterBound));
        return jittered.compareTo(properties.retryMax()) > 0 ? properties.retryMax() : jittered;
    }

    private String envelope(ListingKnowledgeOutboxEvent event) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", event.eventId());
            envelope.put("eventType", event.eventType());
            envelope.put("eventVersion", event.eventVersion());
            envelope.put("occurredAt", event.occurredAt());
            envelope.put("producer", event.producer());
            envelope.put("aggregateType", event.aggregateType());
            envelope.put("aggregateId", event.aggregateId());
            envelope.put("correlationId", event.correlationId());
            envelope.put("payload", objectMapper.readTree(event.payloadJson()));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored listing knowledge event is not valid JSON.", exception);
        }
    }
}
