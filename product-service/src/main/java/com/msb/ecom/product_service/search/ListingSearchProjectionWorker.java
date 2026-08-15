package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        name = "listing.search.projection-sync.enabled",
        havingValue = "true")
@Slf4j
public class ListingSearchProjectionWorker {

    private final ListingSearchProjectionWorkRepository workRepository;
    private final ListingDraftRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final OpenSearchListingSearchClient searchClient;
    private final OpenSearchListingVectorBackfillClient vectorClient;
    private final ListingSearchPromotionRepository promotionRepository;
    private final ListingVectorProjectionDocumentFactory vectorDocumentFactory;
    private final ListingSearchProjectionSyncProperties properties;
    private final ListingSearchProjectionMetrics metrics;
    private final UlidGenerator ulidGenerator;

    public ListingSearchProjectionWorker(
            ListingSearchProjectionWorkRepository workRepository,
            ListingDraftRepository listingRepository,
            ListingMediaRepository mediaRepository,
            OpenSearchListingSearchClient searchClient,
            OpenSearchListingVectorBackfillClient vectorClient,
            ListingSearchPromotionRepository promotionRepository,
            ListingVectorProjectionDocumentFactory vectorDocumentFactory,
            ListingSearchProjectionSyncProperties properties,
            ListingSearchProjectionMetrics metrics,
            UlidGenerator ulidGenerator) {
        if (!searchClient.enabled()) {
            throw new IllegalStateException(
                    "Listing projection synchronization requires listing.search.engine=opensearch.");
        }
        this.workRepository = workRepository;
        this.listingRepository = listingRepository;
        this.mediaRepository = mediaRepository;
        this.searchClient = searchClient;
        this.vectorClient = vectorClient;
        this.promotionRepository = promotionRepository;
        this.vectorDocumentFactory = vectorDocumentFactory;
        this.properties = properties;
        this.metrics = metrics;
        this.ulidGenerator = ulidGenerator;
        metrics.registerPendingGauge(workRepository);
    }

    @Scheduled(fixedDelayString = "${listing.search.projection-sync.poll-interval-ms:5000}")
    // Reconciles committed intents without holding a MySQL transaction during OpenSearch I/O.
    public void processPending() {
        Instant now = Instant.now();
        String claimToken = ulidGenerator.next();
        List<ListingSearchProjectionWork> work = workRepository.claimBatch(
                claimToken,
                now,
                now.plusSeconds(properties.effectiveClaimSeconds()),
                properties.effectiveBatchSize());
        for (ListingSearchProjectionWork item : work) {
            process(item, claimToken);
        }
    }

    private void process(ListingSearchProjectionWork work, String claimToken) {
        try {
            String result = reconcile(work);
            if (workRepository.markApplied(work.workId(), claimToken, Instant.now(), result)) {
                metrics.record("synchronize", result.toLowerCase());
            } else {
                metrics.record("synchronize", "lost_claim");
            }
        } catch (ListingSearchUnavailableException exception) {
            retryOrTerminate(work, claimToken, "OPENSEARCH_UNAVAILABLE");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            workRepository.markTerminal(work.workId(), claimToken, Instant.now(), "INVALID_WORK");
            metrics.record("synchronize", "terminal");
            log.warn("Listing search projection work rejected result=invalid_work");
        } catch (RuntimeException exception) {
            retryOrTerminate(work, claimToken, "UNEXPECTED_FAILURE");
        }
    }

    private String reconcile(ListingSearchProjectionWork work) {
        Optional<ListingDraftResponse> current = listingRepository.findOptionalById(work.listingId());
        if (current.isPresent() && current.get().version() > work.listingVersion()) {
            return "SUPERSEDED";
        }
        if (current.isPresent() && current.get().version() < work.listingVersion()) {
            throw new IllegalArgumentException("Projection work version is ahead of Product authority.");
        }

        boolean searchable = current.filter(ListingSearchProjectionIntentService::isPubliclySearchable).isPresent();
        String requiredOperation = searchable ? "UPSERT" : "DELETE";
        if (!requiredOperation.equals(work.operation())) {
            throw new IllegalArgumentException("Projection work operation does not match Product authority.");
        }
        if (searchable) {
            PublicListingResponse publicListing = listingRepository.findPublicListingById(work.listingId())
                    .orElseThrow(() -> new IllegalStateException("Public listing projection row is missing."));
            var images = mediaRepository.findPublicImagesByListingId(work.listingId());
            ListingSearchDocument activeDocument = ListingSearchDocument.from(publicListing, images);
            long lexicalVersion = ListingVectorExternalVersion.lexical(work.listingVersion());
            searchClient.upsert(activeDocument, lexicalVersion);
            Optional<ListingSearchRebuildRun> run = promotionRepository.findDualWriteRun();
            if (run.isPresent()) {
                vectorClient.upsertCandidate(
                        run.get().candidateGeneration(),
                        vectorDocumentFactory.lexical(current.orElseThrow(), publicListing, images));
            }
            return "UPSERTED";
        }
        long lexicalVersion = ListingVectorExternalVersion.lexical(work.listingVersion());
        searchClient.delete(work.listingId(), lexicalVersion);
        Optional<ListingSearchRebuildRun> run = promotionRepository.findDualWriteRun();
        if (run.isPresent()) {
            vectorClient.deleteCandidate(
                    run.get().candidateGeneration(),
                    work.listingId(),
                    work.listingVersion());
        }
        return "DELETED";
    }

    private void retryOrTerminate(
            ListingSearchProjectionWork work,
            String claimToken,
            String errorCode) {
        int nextAttempt = work.attemptCount() + 1;
        if (nextAttempt >= properties.effectiveMaxAttempts()) {
            workRepository.markTerminal(work.workId(), claimToken, Instant.now(), errorCode);
            metrics.record("synchronize", "terminal");
            log.warn("Listing search projection work exhausted retries errorCode={}", errorCode);
            return;
        }
        Instant nextAttemptAt = Instant.now().plus(retryDelay(work.attemptCount()));
        workRepository.markRetry(work.workId(), claimToken, nextAttemptAt, errorCode);
        metrics.record("synchronize", "retry");
        log.warn("Listing search projection work deferred errorCode={}", errorCode);
    }

    private Duration retryDelay(int retryCount) {
        long multiplier = 1L << Math.min(retryCount, 20);
        Duration candidate;
        try {
            candidate = properties.effectiveRetryBase().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            candidate = properties.effectiveRetryMax();
        }
        return candidate.compareTo(properties.effectiveRetryMax()) > 0
                ? properties.effectiveRetryMax()
                : candidate;
    }
}
