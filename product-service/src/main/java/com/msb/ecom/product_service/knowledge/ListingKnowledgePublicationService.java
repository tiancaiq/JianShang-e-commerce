package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ListingKnowledgePublicationService {

    private static final String LANGUAGE = "und";
    private static final String PRODUCER = "product-service";

    private final ListingKnowledgeRepository repository;
    private final ListingKnowledgeCanonicalHasher canonicalHasher;
    private final ListingKnowledgePublicationProperties properties;
    private final UlidGenerator ulidGenerator;
    private final ObjectMapper objectMapper;

    public ListingKnowledgePublicationService(
            ListingKnowledgeRepository repository,
            ListingKnowledgeCanonicalHasher canonicalHasher,
            ListingKnowledgePublicationProperties properties,
            UlidGenerator ulidGenerator,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.canonicalHasher = canonicalHasher;
        this.properties = properties;
        this.ulidGenerator = ulidGenerator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    // Reconciles one committed listing version change into an immutable source version and the same transaction's outbox.
    public void reconcile(ListingDraftResponse before, ListingDraftResponse after, Instant occurredAt) {
        boolean wasEligible = eligible(before);
        boolean isEligible = eligible(after);
        if (!wasEligible && !isEligible) {
            return;
        }
        if (!before.id().equals(after.id()) || after.version() <= before.version()) {
            throw new IllegalStateException("Listing knowledge publication requires a newer version of the same listing.");
        }

        String eventType;
        Long supersedesVersion;
        String lifecycle;
        if (isEligible) {
            supersedesVersion = wasEligible ? before.version() : null;
            eventType = wasEligible ? "listing.updated" : "listing.activated";
            lifecycle = "ACTIVE";
            repository.insertActive(activeVersion(after, supersedesVersion, occurredAt));
        } else {
            supersedesVersion = before.version();
            eventType = "listing.deactivated";
            lifecycle = "INVALIDATED";
            repository.insertInvalidated(invalidatedVersion(after, supersedesVersion, occurredAt));
        }
        insertOutbox(after, supersedesVersion, lifecycle, eventType, occurredAt);
    }

    @Transactional
    // Seeds an already-active listing after deployment without reading or exposing private listing fields.
    public void bootstrap(ListingDraftResponse current, Instant occurredAt) {
        if (!eligible(current) || repository.findExact(current.id(), current.version()).isPresent()) {
            return;
        }
        repository.insertActive(activeVersion(current, null, occurredAt));
        insertOutbox(current, null, "ACTIVE", "listing.activated", occurredAt);
    }

    private boolean eligible(ListingDraftResponse listing) {
        return listing != null
                && "INDIVIDUAL".equals(listing.sellerType())
                && "ACTIVE".equals(listing.status())
                && "APPROVED".equals(listing.moderationStatus());
    }

    private ListingKnowledgeVersion activeVersion(
            ListingDraftResponse listing,
            Long supersedesVersion,
            Instant occurredAt) {
        return new ListingKnowledgeVersion(
                listing.id(),
                listing.version(),
                supersedesVersion,
                "ACTIVE",
                "INDIVIDUAL",
                "PUBLIC",
                LANGUAGE,
                listing.title(),
                listing.description(),
                listing.priceAmount(),
                listing.currency(),
                listing.publicCity(),
                listing.publicRegion(),
                canonicalHasher.hash(listing),
                occurredAt,
                null,
                listing.publishedAt(),
                occurredAt);
    }

    private ListingKnowledgeVersion invalidatedVersion(
            ListingDraftResponse listing,
            long supersedesVersion,
            Instant occurredAt) {
        return new ListingKnowledgeVersion(
                listing.id(),
                listing.version(),
                supersedesVersion,
                "INVALIDATED",
                "INDIVIDUAL",
                "PUBLIC",
                LANGUAGE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                occurredAt,
                null,
                occurredAt);
    }

    private void insertOutbox(
            ListingDraftResponse listing,
            Long supersedesVersion,
            String lifecycle,
            String eventType,
            Instant occurredAt) {
        String eventId = ulidGenerator.next();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("listingId", listing.id());
        payload.put("listingVersion", Long.toString(listing.version()));
        payload.put("knowledgeLifecycle", lifecycle);
        payload.put("supersedesVersion", supersedesVersion == null ? null : Long.toString(supersedesVersion));
        payload.put("language", LANGUAGE);

        ListingKnowledgeOutboxEvent event = new ListingKnowledgeOutboxEvent(
                eventId,
                properties.topic(),
                listing.id(),
                "listing",
                listing.id(),
                eventType,
                1,
                PRODUCER,
                occurredAt,
                correlationId(eventId),
                json(payload),
                0,
                occurredAt);
        repository.insertOutbox(
                event,
                "LISTING_KNOWLEDGE:" + listing.id() + ":" + listing.version());
    }

    private String correlationId(String fallback) {
        String correlationId = MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank() ? fallback : correlationId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Listing knowledge event could not be serialized.", exception);
        }
    }
}
