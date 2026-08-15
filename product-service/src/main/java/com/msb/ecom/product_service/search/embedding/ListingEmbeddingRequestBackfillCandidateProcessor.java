package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class ListingEmbeddingRequestBackfillCandidateProcessor {
    private final ListingDraftRepository listingRepository;
    private final ListingDiscoveryEmbeddingRequestService requestService;
    private final Clock clock;

    // Revalidates and processes one listing in a small independent request/outbox transaction.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CandidateOutcome process(String listingId) {
        ListingDraftResponse current = listingRepository.findOptionalById(listingId)
                .orElse(null);
        if (!ListingDiscoveryEmbeddingRequestService.eligible(current)) {
            return CandidateOutcome.SKIPPED;
        }
        return switch (requestService.createForCurrentEligibleVersion(
                current,
                clock.instant())) {
            case CREATED -> CandidateOutcome.CREATED;
            case ALREADY_PRESENT -> CandidateOutcome.ALREADY_PRESENT;
            case INELIGIBLE -> CandidateOutcome.SKIPPED;
            case DISABLED -> throw new IllegalStateException(
                    "Listing embedding requests became disabled.");
        };
    }

    public enum CandidateOutcome {
        CREATED,
        ALREADY_PRESENT,
        SKIPPED
    }
}
