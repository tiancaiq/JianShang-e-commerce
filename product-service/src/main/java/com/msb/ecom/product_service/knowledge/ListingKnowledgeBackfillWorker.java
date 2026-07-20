package com.msb.ecom.product_service.knowledge;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(
        name = "listing.knowledge.publication.backfill-enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class ListingKnowledgeBackfillWorker {

    private static final int BATCH_SIZE = 100;

    private final ListingDraftRepository listingDraftRepository;
    private final ListingKnowledgePublicationService publicationService;

    public ListingKnowledgeBackfillWorker(
            ListingDraftRepository listingDraftRepository,
            ListingKnowledgePublicationService publicationService) {
        this.listingDraftRepository = listingDraftRepository;
        this.publicationService = publicationService;
    }

    @Scheduled(
            initialDelayString = "${listing.knowledge.publication.backfill-initial-delay-ms:1000}",
            fixedDelayString = "${listing.knowledge.publication.backfill-interval-ms:60000}")
    // Backfills one bounded batch so pre-existing active listings become exportable without blocking application startup.
    public void backfillCurrentActiveListings() {
        List<ListingDraftResponse> listings =
                listingDraftRepository.findActiveIndividualsMissingKnowledgeVersion(BATCH_SIZE);
        for (ListingDraftResponse listing : listings) {
            try {
                publicationService.bootstrap(listing, Instant.now());
            } catch (RuntimeException exception) {
                log.warn("Listing knowledge backfill failed listingId={} sourceVersion={} errorCode=BACKFILL_FAILED",
                        listing.id(), listing.version());
            }
        }
        if (!listings.isEmpty()) {
            log.info("Listing knowledge backfill processed count={}", listings.size());
        }
    }
}
