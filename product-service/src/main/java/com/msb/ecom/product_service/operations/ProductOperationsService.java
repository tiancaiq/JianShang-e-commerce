package com.msb.ecom.product_service.operations;

import com.msb.ecom.product_service.search.ListingSearchProjectionSyncProperties;
import com.msb.ecom.product_service.search.ListingSearchVectorSyncProperties;
import com.msb.ecom.product_service.search.operator.ListingSearchOperatorProperties;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static com.msb.ecom.product_service.operations.ProductOperationsContracts.*;

@Service
@RequiredArgsConstructor
public class ProductOperationsService {
    private final ProductOperationsRepository repository;
    private final ListingSearchProjectionSyncProperties projection;
    private final ListingSearchVectorSyncProperties vector;
    private final ListingSearchOperatorProperties operator;
    private final UlidGenerator ids;
    private final Clock clock;
    @Value("${listing.knowledge.publication.publisher-enabled:false}")
    private boolean knowledgePublisherEnabled;
    @Value("${listing.knowledge.publication.backfill-enabled:true}")
    private boolean knowledgeBackfillEnabled;

    public Snapshot snapshot() {
        Instant now = clock.instant();
        List<Job> jobs = repository.jobs(now, projection.effectiveMaxAttempts(), vector.effectiveMaxAttempts());
        return new Snapshot("PRODUCT", true, "Product operational signals loaded.", jobs,
                repository.outbox(now), List.of(), List.of(), repository.searchStatus(jobs),
                List.of(
                        feature("LISTING_SEARCH_PROJECTION", "Listing search projection",
                                projection.enabled(), "Product runtime configuration",
                                "Queues authoritative listing changes for the derived search index."),
                        feature("LISTING_VECTOR_SYNC", "Listing vector synchronization",
                                vector.enabled() && vector.workerEnabled(), "Product runtime configuration",
                                "Applies prepared listing vectors when the optional vector pipeline is enabled."),
                        feature("SEARCH_MAINTENANCE_STATUS", "Legacy Search Maintenance status",
                                operator.statusEnabled(), "Product runtime configuration",
                                "The older full-rebuild status boundary remains independently gated."),
                        feature("SEARCH_MAINTENANCE_COMMANDS", "Legacy Search Maintenance commands",
                                operator.commandsEnabled(), "Product runtime configuration",
                                "Full rebuild commands remain outside bounded ADM-SYS recovery."),
                        feature("CATEGORY_GUIDANCE_PUBLICATION", "Category Guidance publication",
                                knowledgePublisherEnabled, "Product runtime configuration",
                                "Publishes curated Category Guidance sources without enabling AI in this console."),
                        feature("LISTING_KNOWLEDGE_BACKFILL", "Listing knowledge backfill",
                                knowledgeBackfillEnabled, "Product runtime configuration",
                                "Runs the existing bounded knowledge-source backfill worker.")));
    }

    @Transactional
    // Revalidates the target inside Product and only queues work for the existing worker.
    public Action action(ActionRequest request) {
        if (request == null || request.targetType() == null || request.targetId() == null
                || request.commandType() == null) {
            throw new IllegalArgumentException("A bounded operational target is required.");
        }
        return switch (request.commandType()) {
            case "RETRY_JOB" -> retryJob(request);
            case "RETRY_OUTBOX" -> retryOutbox(request);
            case "REINDEX_LISTING" -> reindex(request);
            default -> unsupported(request, "This Product operation is not supported.");
        };
    }

    private Action retryJob(ActionRequest request) {
        Job job = repository.job(request.targetId(), clock.instant(), projection.effectiveMaxAttempts(),
                vector.effectiveMaxAttempts()).orElse(null);
        if (job == null) return unsupported(request, "The Product job was not found.");
        boolean allowed = job.retryable();
        if (!request.dryRun() && allowed) allowed = repository.retryJob(job.jobId(), clock.instant());
        return new Action("PRODUCT", "JOB", job.jobId(), request.commandType(), job.status(), allowed,
                allowed ? null : "JOB_NOT_RETRYABLE", List.of("Product search worker"),
                List.of("Attempt history is preserved; the request does not mark the job successful."),
                "Move the existing failed work item to the front of its worker queue.",
                "Search visibility may remain stale until the worker completes.",
                request.dryRun() ? null : allowed ? "ACCEPTED" : "NOT_RETRYABLE",
                allowed ? "Product accepted the retry request for its existing worker."
                        : "The job is not currently retryable.");
    }

    private Action retryOutbox(ActionRequest request) {
        Outbox event = repository.outbox(request.targetId(), clock.instant()).orElse(null);
        if (event == null) return unsupported(request, "The Product outbox event was not found.");
        boolean allowed = event.retryable();
        if (!request.dryRun() && allowed) allowed = repository.retryOutbox(event.eventId(), clock.instant());
        return new Action("PRODUCT", "OUTBOX_EVENT", event.eventId(), request.commandType(),
                event.status(), allowed, allowed ? null : "OUTBOX_EVENT_NOT_RETRYABLE",
                List.of("Product outbox publisher", "Consumer event-ID deduplication"),
                List.of("A consumer may have observed a prior uncertain delivery."),
                "Schedule the existing unpublished event for publisher pickup.",
                "No listing state is changed by requesting the replay.",
                request.dryRun() ? null : allowed ? "ACCEPTED" : "NOT_RETRYABLE",
                allowed ? "Product accepted the outbox retry request."
                        : "The event is not currently retryable.");
    }

    private Action reindex(ActionRequest request) {
        ProductOperationsRepository.ListingState listing = (request.dryRun()
                ? repository.listing(request.targetId())
                : repository.listingForUpdate(request.targetId())).orElse(null);
        if (listing == null) return unsupported(request, "The listing was not found.");
        boolean allowed = projection.enabled();
        if (!request.dryRun() && allowed) {
            repository.reindex(listing, ids.next(), request.correlationId(), clock.instant());
        }
        return new Action("PRODUCT", "SEARCH_LISTING", listing.id(), request.commandType(),
                listing.status(), allowed, allowed ? null : "SEARCH_REINDEX_NOT_ALLOWED",
                List.of("Product listing source of truth", "Listing search projection worker"),
                List.of("The current authoritative listing state determines UPSERT versus DELETE."),
                "Queue one current-state listing projection operation.",
                "Marketplace listing status, moderation, price, and ownership remain unchanged.",
                request.dryRun() ? null : allowed ? "ACCEPTED" : "NOT_RETRYABLE",
                allowed ? "Product accepted one bounded listing reindex request."
                        : "Listing search projection is disabled.");
    }

    private Action unsupported(ActionRequest request, String message) {
        return new Action("PRODUCT", request.targetType(), request.targetId(), request.commandType(),
                "UNKNOWN", false, "SYSTEM_OPERATION_NOT_SUPPORTED", List.of(), List.of(),
                "No operation will run.", "No customer data is changed.",
                request.dryRun() ? null : "NOT_RETRYABLE", message);
    }

    private Feature feature(String key, String name, boolean enabled, String source, String summary) {
        return new Feature(key, name, enabled ? "ENABLED" : "DISABLED", "SERVICE", source,
                null, false, summary);
    }
}
