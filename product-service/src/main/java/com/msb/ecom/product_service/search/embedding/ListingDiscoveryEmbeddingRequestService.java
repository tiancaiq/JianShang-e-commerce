package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.knowledge.ListingKnowledgeOutboxEvent;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ListingDiscoveryEmbeddingRequestService {

    private static final String EVENT_TYPE = "listing.discovery.embedding-requested";
    private static final String PRODUCER = "product-service";

    private final ListingDiscoveryEmbeddingProperties properties;
    private final ListingDiscoveryEmbeddingRequestRepository repository;
    private final ListingDraftRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;
    private final ListingDiscoveryEmbeddingMetrics metrics;
    private final UlidGenerator ulidGenerator;
    private final ObjectMapper objectMapper;

    public ListingDiscoveryEmbeddingRequestService(
            ListingDiscoveryEmbeddingProperties properties,
            ListingDiscoveryEmbeddingRequestRepository repository,
            ListingDraftRepository listingRepository,
            ListingMediaRepository mediaRepository,
            ListingDiscoveryEmbeddingSourceBuilder sourceBuilder,
            ListingDiscoveryEmbeddingMetrics metrics,
            UlidGenerator ulidGenerator,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.repository = repository;
        this.listingRepository = listingRepository;
        this.mediaRepository = mediaRepository;
        this.sourceBuilder = sourceBuilder;
        this.metrics = metrics;
        this.ulidGenerator = ulidGenerator;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    // Persists one version-bound request and its reference-only event inside the listing mutation transaction.
    public RequestOutcome createForCurrentEligibleVersion(
            ListingDraftResponse listing,
            Instant occurredAt) {
        if (!properties.requestEnabled()) {
            metrics.record("request", "disabled");
            return RequestOutcome.DISABLED;
        }
        if (!eligible(listing)) {
            metrics.record("request", "ineligible");
            return RequestOutcome.INELIGIBLE;
        }

        PublicListingResponse publicListing = listingRepository.findPublicListingById(listing.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Eligible listing discovery source could not be resolved."));
        ListingDiscoveryEmbeddingSource source = sourceBuilder.build(
                publicListing,
                listing.version(),
                mediaRepository.findPublicImagesByListingId(listing.id()));
        String requestId = ulidGenerator.next();
        String eventId = ulidGenerator.next();
        ListingDiscoveryEmbeddingRequest request = new ListingDiscoveryEmbeddingRequest(
                requestId,
                eventId,
                listing.id(),
                listing.version(),
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                ListingDiscoveryEmbeddingSourceBuilder.PROVIDER,
                ListingDiscoveryEmbeddingSourceBuilder.MODEL,
                ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS,
                "REQUESTED",
                occurredAt);
        if (!repository.insertIfAbsent(request)) {
            metrics.record("request", "replay");
            return RequestOutcome.ALREADY_PRESENT;
        }

        repository.insertOutbox(outbox(request), "LISTING_DISCOVERY_EMBEDDING:" + requestId);
        metrics.record("request", "created");
        return RequestOutcome.CREATED;
    }

    public enum RequestOutcome {
        CREATED,
        ALREADY_PRESENT,
        INELIGIBLE,
        DISABLED
    }

    static boolean eligible(ListingDraftResponse listing) {
        return listing != null
                && "INDIVIDUAL".equals(listing.sellerType())
                && "ACTIVE".equals(listing.status())
                && "APPROVED".equals(listing.moderationStatus());
    }

    private ListingKnowledgeOutboxEvent outbox(ListingDiscoveryEmbeddingRequest request) {
        Map<String, Object> embeddingIdentity = new LinkedHashMap<>();
        embeddingIdentity.put("provider", request.provider());
        embeddingIdentity.put("model", request.model());
        embeddingIdentity.put("dimensions", request.dimensions());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.requestId());
        payload.put("listingId", request.listingId());
        payload.put("listingVersion", request.listingVersion());
        payload.put("documentSchemaVersion", request.documentSchemaVersion());
        payload.put("documentHash", request.documentHash());
        payload.put("embeddingInputSchemaVersion", request.embeddingInputSchemaVersion());
        payload.put("embeddingInputHash", request.embeddingInputHash());
        payload.put("normalizerVersion", request.normalizerVersion());
        payload.put("redactorVersion", request.redactorVersion());
        payload.put("language", request.language());
        payload.put("embeddingIdentity", embeddingIdentity);

        return new ListingKnowledgeOutboxEvent(
                request.eventId(),
                properties.topic(),
                request.listingId(),
                "listing",
                request.listingId(),
                EVENT_TYPE,
                1,
                PRODUCER,
                request.createdAt(),
                correlationId(request.eventId()),
                json(payload),
                0,
                request.createdAt());
    }

    private String correlationId(String fallback) {
        String correlationId = MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank() ? fallback : correlationId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Listing discovery embedding event could not be serialized.", exception);
        }
    }
}
