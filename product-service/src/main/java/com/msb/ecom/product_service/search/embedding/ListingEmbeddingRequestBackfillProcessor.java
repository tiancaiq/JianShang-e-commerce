package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ListingEmbeddingRequestBackfillProcessor {
    private static final String ACTIVE = "LISTING_EMBEDDING_BACKFILL_ACTIVE";
    private static final String BUSY = "LISTING_EMBEDDING_BACKFILL_BUSY";
    private static final String BOUND = "LISTING_EMBEDDING_BACKFILL_BOUND_EXCEEDED";
    private static final String UNAVAILABLE = "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE";

    private final ListingEmbeddingRequestBackfillProperties properties;
    private final ListingDiscoveryEmbeddingProperties embeddingProperties;
    private final ListingEmbeddingRequestBackfillRepository repository;
    private final ListingDraftRepository listingRepository;
    private final ListingEmbeddingRequestBackfillCandidateProcessor candidateProcessor;
    private final ListingDiscoveryEmbeddingMetrics metrics;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;

    // Captures a finite catalog watermark and processes at most one bounded page.
    public ListingEmbeddingRequestBackfillRun start() {
        requireCommandGates();
        repository.findActive().ifPresent(active -> {
            throw conflict(ACTIVE);
        });
        Instant now = clock.instant();
        String runId = ulidGenerator.next();
        String upperBound = listingRepository.findMaximumEligibleIndividualListingId()
                .orElse(null);
        try {
            repository.insert(runId, upperBound, now);
        } catch (DuplicateKeyException exception) {
            throw conflict(ACTIVE);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        metrics.record("request_backfill", "started");
        return processOnePage(runId);
    }

    // Resumes one failed, pending, or lease-expired page without replaying completed work effects.
    public ListingEmbeddingRequestBackfillRun resume(String runId) {
        requireCommandGates();
        return processOnePage(runId);
    }

    public ListingEmbeddingRequestBackfillRun status(String runId) {
        return repository.findByRunId(runId)
                .orElseThrow(() -> new ListingEmbeddingRequestBackfillException(
                        ListingEmbeddingRequestBackfillException.Kind.NOT_FOUND,
                        "LISTING_EMBEDDING_BACKFILL_NOT_FOUND"));
    }

    private ListingEmbeddingRequestBackfillRun processOnePage(String runId) {
        ListingEmbeddingRequestBackfillRun before = status(runId);
        if (before.completed()) {
            metrics.record("request_backfill", "replay");
            return before;
        }

        String leaseToken = ulidGenerator.next();
        Instant now = clock.instant();
        if (!repository.claim(
                runId,
                leaseToken,
                now,
                now.plus(properties.leaseDuration()))) {
            ListingEmbeddingRequestBackfillRun current = status(runId);
            if (current.completed()) {
                metrics.record("request_backfill", "replay");
                return current;
            }
            throw conflict(BUSY);
        }

        try {
            ListingEmbeddingRequestBackfillRun claimed = status(runId);
            if (claimed.processedCount() >= properties.maxListings()) {
                repository.fail(runId, leaseToken, BOUND, clock.instant());
                metrics.record("request_backfill", "bound_exceeded");
                throw conflict(BOUND);
            }

            int remaining = properties.maxListings() - claimed.processedCount();
            int pageLimit = Math.min(properties.pageSize(), remaining);
            List<String> candidates =
                    listingRepository.findEligibleIndividualListingIdsForEmbeddingBackfill(
                            claimed.lastProcessedListingId(),
                            claimed.upperBoundListingId(),
                            pageLimit + 1);
            boolean hasMore = candidates.size() > pageLimit;
            List<String> page = hasMore
                    ? candidates.subList(0, pageLimit)
                    : candidates;
            ListingEmbeddingRequestBackfillPageResult result = process(page, hasMore);
            if (!repository.completePage(runId, leaseToken, result, clock.instant())) {
                throw unavailable(null);
            }
            ListingEmbeddingRequestBackfillRun after = status(runId);
            metrics.record(
                    "request_backfill",
                    after.completed() ? "completed" : "page_completed");
            return after;
        } catch (ListingEmbeddingRequestBackfillException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            repository.fail(runId, leaseToken, UNAVAILABLE, clock.instant());
            metrics.record("request_backfill", "failed");
            throw unavailable(exception);
        }
    }

    private ListingEmbeddingRequestBackfillPageResult process(
            List<String> listingIds,
            boolean hasMore) {
        int created = 0;
        int alreadyPresent = 0;
        int skipped = 0;
        String lastProcessed = null;
        for (String listingId : listingIds) {
            switch (candidateProcessor.process(listingId)) {
                case CREATED -> created++;
                case ALREADY_PRESENT -> alreadyPresent++;
                case SKIPPED -> skipped++;
            }
            lastProcessed = listingId;
        }
        return new ListingEmbeddingRequestBackfillPageResult(
                listingIds.size(),
                created,
                alreadyPresent,
                skipped,
                lastProcessed,
                hasMore);
    }

    private void requireCommandGates() {
        if (!properties.commandsEnabled()) {
            throw new ListingEmbeddingRequestBackfillException(
                    ListingEmbeddingRequestBackfillException.Kind.DISABLED,
                    "FEATURE_DISABLED");
        }
        if (!embeddingProperties.requestEnabled()) {
            throw unavailable(null);
        }
    }

    private ListingEmbeddingRequestBackfillException conflict(String code) {
        return new ListingEmbeddingRequestBackfillException(
                ListingEmbeddingRequestBackfillException.Kind.CONFLICT,
                code);
    }

    private ListingEmbeddingRequestBackfillException unavailable(Throwable cause) {
        return new ListingEmbeddingRequestBackfillException(
                ListingEmbeddingRequestBackfillException.Kind.UNAVAILABLE,
                UNAVAILABLE,
                cause);
    }
}
