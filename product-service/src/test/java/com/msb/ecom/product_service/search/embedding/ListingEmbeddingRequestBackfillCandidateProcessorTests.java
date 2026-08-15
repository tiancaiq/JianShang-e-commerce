package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingEmbeddingRequestBackfillCandidateProcessorTests {
    private static final String LISTING_ID = "01D00000000000000000000911";
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private final ListingDraftRepository listingRepository = mock(ListingDraftRepository.class);
    private final ListingDiscoveryEmbeddingRequestService requestService = mock(
            ListingDiscoveryEmbeddingRequestService.class);
    private final ListingEmbeddingRequestBackfillCandidateProcessor processor =
            new ListingEmbeddingRequestBackfillCandidateProcessor(
                    listingRepository,
                    requestService,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reloadsCurrentEligibleVersionAndMapsCanonicalCreationOutcome() {
        ListingDraftResponse listing = listing("ACTIVE", "APPROVED");
        when(listingRepository.findOptionalById(LISTING_ID)).thenReturn(Optional.of(listing));
        when(requestService.createForCurrentEligibleVersion(listing, NOW))
                .thenReturn(ListingDiscoveryEmbeddingRequestService.RequestOutcome.CREATED);

        assertThat(processor.process(LISTING_ID))
                .isEqualTo(ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.CREATED);
    }

    @Test
    void missingOrNewlyIneligibleListingIsSkippedBeforeCanonicalRequestWork() {
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.empty(), Optional.of(listing("DRAFT", "NOT_SUBMITTED")));

        assertThat(processor.process(LISTING_ID))
                .isEqualTo(ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.SKIPPED);
        assertThat(processor.process(LISTING_ID))
                .isEqualTo(ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.SKIPPED);

        verifyNoInteractions(requestService);
    }

    private ListingDraftResponse listing(String status, String moderationStatus) {
        return new ListingDraftResponse(
                LISTING_ID,
                "INDIVIDUAL",
                "01U00000000000000000000911",
                null,
                null,
                "Seller",
                "01C00000000000000000000911",
                "Walnut radio",
                "Public description",
                "GOOD",
                null,
                BigDecimal.valueOf(38),
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
                0,
                NOW,
                NOW,
                null,
                null,
                null,
                java.util.List.of());
    }
}
