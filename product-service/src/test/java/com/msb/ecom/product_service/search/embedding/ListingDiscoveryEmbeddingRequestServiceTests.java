package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.knowledge.ListingKnowledgeOutboxEvent;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingDiscoveryEmbeddingRequestServiceTests {

    private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String EVENT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAD";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @Mock ListingDiscoveryEmbeddingRequestRepository repository;
    @Mock ListingDraftRepository listingRepository;
    @Mock ListingMediaRepository mediaRepository;
    @Mock ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;
    @Mock ListingDiscoveryEmbeddingMetrics metrics;
    @Mock UlidGenerator ulidGenerator;

    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void eligibleVersionCreatesOneMetadataRequestAndReferenceOnlyOutboxEvent() throws Exception {
        when(ulidGenerator.next()).thenReturn(REQUEST_ID, EVENT_ID);
        ListingDraftResponse listing = listing("ACTIVE", "APPROVED", 7);
        when(listingRepository.findPublicListingById(LISTING_ID)).thenReturn(Optional.of(publicListing()));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());
        when(sourceBuilder.build(any(), any(Long.class), any())).thenReturn(source());
        when(repository.insertIfAbsent(any())).thenReturn(true);
        ListingDiscoveryEmbeddingRequestService service = service(true);

        service.createForCurrentEligibleVersion(listing, NOW);

        ArgumentCaptor<ListingDiscoveryEmbeddingRequest> requestCaptor =
                ArgumentCaptor.forClass(ListingDiscoveryEmbeddingRequest.class);
        verify(repository).insertIfAbsent(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .extracting(
                        ListingDiscoveryEmbeddingRequest::requestId,
                        ListingDiscoveryEmbeddingRequest::listingId,
                        ListingDiscoveryEmbeddingRequest::listingVersion,
                        ListingDiscoveryEmbeddingRequest::provider,
                        ListingDiscoveryEmbeddingRequest::model,
                        ListingDiscoveryEmbeddingRequest::dimensions)
                .containsExactly(
                        REQUEST_ID,
                        LISTING_ID,
                        7L,
                        "openai",
                        "text-embedding-3-small",
                        1536);

        ArgumentCaptor<ListingKnowledgeOutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(ListingKnowledgeOutboxEvent.class);
        verify(repository).insertOutbox(eventCaptor.capture(), anyString());
        ListingKnowledgeOutboxEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo("listing.discovery.embedding-requested");
        assertThat(event.eventVersion()).isEqualTo(1);
        JsonNode payload = objectMapper.readTree(event.payloadJson());
        assertThat(payload.path("requestId").asText()).isEqualTo(REQUEST_ID);
        assertThat(payload.path("listingVersion").asLong()).isEqualTo(7);
        assertThat(payload.path("embeddingIdentity").path("dimensions").asInt()).isEqualTo(1536);
        assertThat(event.payloadJson())
                .doesNotContain(
                        "title",
                        "description",
                        "seller",
                        "email",
                        "phone",
                        "publicCity",
                        "storage",
                        "vector",
                        "prompt",
                        "providerResponse");
    }

    @Test
    void disabledAndIneligibleVersionsPerformZeroRepositoryOrOutboxWork() {
        service(false).createForCurrentEligibleVersion(listing("ACTIVE", "APPROVED", 7), NOW);
        service(true).createForCurrentEligibleVersion(listing("DRAFT", "NOT_SUBMITTED", 7), NOW);

        verifyNoInteractions(repository, listingRepository, mediaRepository, sourceBuilder);
        verify(metrics).record("request", "disabled");
        verify(metrics).record("request", "ineligible");
    }

    @Test
    void exactDuplicateDoesNotCreateAnotherOutboxEvent() {
        when(ulidGenerator.next()).thenReturn(REQUEST_ID, EVENT_ID);
        when(listingRepository.findPublicListingById(LISTING_ID)).thenReturn(Optional.of(publicListing()));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());
        when(sourceBuilder.build(any(), any(Long.class), any())).thenReturn(source());
        when(repository.insertIfAbsent(any())).thenReturn(false);

        service(true).createForCurrentEligibleVersion(listing("ACTIVE", "APPROVED", 7), NOW);

        verify(repository, never()).insertOutbox(any(), anyString());
        verify(metrics).record("request", "replay");
    }

    private ListingDiscoveryEmbeddingRequestService service(boolean enabled) {
        return new ListingDiscoveryEmbeddingRequestService(
                new ListingDiscoveryEmbeddingProperties(
                        enabled,
                        false,
                        false,
                        "listing-discovery-embedding-request-v1"),
                repository,
                listingRepository,
                mediaRepository,
                sourceBuilder,
                metrics,
                ulidGenerator,
                objectMapper);
    }

    private ListingDiscoveryEmbeddingSource source() {
        return new ListingDiscoveryEmbeddingSource(
                "MARKETPLACE_LISTING_DISCOVERY_V2",
                "a".repeat(64),
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
                "b".repeat(64),
                "NFKC_WHITESPACE_V1",
                "PUBLIC_CONTACT_REDACTION_V1",
                "und",
                "TITLE\nDesk\nCATEGORY\nFurniture\nfurniture\nDESCRIPTION\nPublic description");
    }

    private ListingDraftResponse listing(String status, String moderationStatus, long version) {
        return new ListingDraftResponse(
                LISTING_ID,
                "INDIVIDUAL",
                "01ARZ3NDEKTSV4RRFFQ69G5FAA",
                null,
                null,
                null,
                "01ARZ3NDEKTSV4RRFFQ69G5FAB",
                "Desk",
                "Public description",
                "GOOD",
                null,
                java.math.BigDecimal.TEN,
                "USD",
                false,
                null,
                1,
                "Irvine",
                "Orange County",
                status,
                moderationStatus,
                "ADMIN_REVIEW",
                NOW,
                version,
                NOW,
                NOW,
                null,
                null,
                null,
                List.of());
    }

    private PublicListingResponse publicListing() {
        return new PublicListingResponse(
                LISTING_ID,
                "INDIVIDUAL",
                "01ARZ3NDEKTSV4RRFFQ69G5FAA",
                null,
                null,
                null,
                null,
                null,
                false,
                "01ARZ3NDEKTSV4RRFFQ69G5FAB",
                "furniture",
                "Furniture",
                "Desk",
                "Public description",
                "GOOD",
                null,
                java.math.BigDecimal.TEN,
                "USD",
                false,
                1,
                "Irvine",
                "Orange County",
                NOW,
                null,
                0,
                0,
                List.of());
    }
}
