package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingKnowledgeOutboxPublisherTests {

    @Test
    void acknowledgedSendMarksClaimPublishedWithReferenceOnlyEnvelope() throws Exception {
        ListingKnowledgeRepository repository = mock(ListingKnowledgeRepository.class);
        ListingKnowledgePublicationMetrics metrics = mock(ListingKnowledgePublicationMetrics.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ListingKnowledgeOutboxEvent event = event(0);
        when(repository.claimBatch(anyString(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(repository.markPublished(anyString(), anyString(), any())).thenReturn(true);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));

        publisher(repository, metrics, kafkaTemplate, objectMapper).publishPending();

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("listing-knowledge-v1"),
                org.mockito.ArgumentMatchers.eq(event.messageKey()),
                envelope.capture());
        JsonNode json = objectMapper.readTree(envelope.getValue());
        assertThat(json.path("eventId").asText()).isEqualTo(event.eventId());
        assertThat(json.path("payload").path("listingId").asText()).isEqualTo(event.aggregateId());
        assertThat(json.path("payload").has("title")).isFalse();
        verify(repository).markPublished(
                org.mockito.ArgumentMatchers.eq(event.eventId()),
                anyString(),
                any());
        verify(repository, never()).markFailed(anyString(), anyString(), any(), anyString());
        verify(metrics).published();
    }

    @Test
    void failedSendReleasesClaimForBoundedRetry() {
        ListingKnowledgeRepository repository = mock(ListingKnowledgeRepository.class);
        ListingKnowledgePublicationMetrics metrics = mock(ListingKnowledgePublicationMetrics.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        ListingKnowledgeOutboxEvent event = event(2);
        when(repository.claimBatch(anyString(), any(), any(), anyInt())).thenReturn(List.of(event));
        when(repository.markFailed(anyString(), anyString(), any(), anyString())).thenReturn(true);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        publisher(repository, metrics, kafkaTemplate, new ObjectMapper().findAndRegisterModules()).publishPending();

        verify(repository).markFailed(
                org.mockito.ArgumentMatchers.eq(event.eventId()),
                anyString(),
                any(),
                org.mockito.ArgumentMatchers.eq("KAFKA_SEND_FAILED"));
        verify(repository, never()).markPublished(anyString(), anyString(), any());
        verify(metrics).failed("KAFKA_SEND_FAILED");
    }

    private ListingKnowledgeOutboxPublisher publisher(
            ListingKnowledgeRepository repository,
            ListingKnowledgePublicationMetrics metrics,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        return new ListingKnowledgeOutboxPublisher(
                repository,
                new ListingKnowledgePublicationProperties(
                        false,
                        true,
                        "listing-knowledge-v1",
                        10,
                        5000,
                        60,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(5)),
                metrics,
                kafkaTemplate,
                objectMapper,
                new UlidGenerator(),
                mock(CategoryGuidanceMetrics.class));
    }

    private ListingKnowledgeOutboxEvent event(int retryCount) {
        return new ListingKnowledgeOutboxEvent(
                "01E00000000000000000000001",
                "listing-knowledge-v1",
                "01L00000000000000000000001",
                "listing",
                "01L00000000000000000000001",
                "listing.activated",
                1,
                "product-service",
                Instant.parse("2026-07-18T12:00:00Z"),
                "01C00000000000000000000001",
                """
                        {"listingId":"01L00000000000000000000001","listingVersion":"1",
                         "knowledgeLifecycle":"ACTIVE","supersedesVersion":null,"language":"und"}
                        """,
                retryCount,
                Instant.parse("2026-07-18T12:00:00Z"));
    }
}
