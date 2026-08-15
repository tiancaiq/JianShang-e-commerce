package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingSearchProjectionIntentServiceTests {

    private static final String LISTING_ID = "01L00000000000000000000231";
    private static final String WORK_ID = "01W00000000000000000000231";
    private static final Instant NOW = Instant.parse("2026-07-23T09:00:00Z");

    private final ListingDraftRepository listingRepository = mock(ListingDraftRepository.class);
    private final ListingSearchProjectionWorkRepository workRepository =
            mock(ListingSearchProjectionWorkRepository.class);
    private final ListingSearchPromotionRepository promotionRepository =
            mock(ListingSearchPromotionRepository.class);
    private final UlidGenerator ulidGenerator = mock(UlidGenerator.class);

    @Test
    void disabledCapabilityDoesNoDatabaseWork() {
        service(false).recordCurrentState(LISTING_ID, NOW);

        verifyNoInteractions(listingRepository, workRepository, promotionRepository, ulidGenerator);
    }

    @Test
    void activeApprovedIndividualCreatesVersionedUpsertIntent() {
        ListingDraftResponse listing = listing("INDIVIDUAL", "ACTIVE", "APPROVED", null, 7);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        when(ulidGenerator.next()).thenReturn(WORK_ID);

        service(true).recordCurrentState(LISTING_ID, NOW);

        verify(promotionRepository).acquireMutationFenceShared(5);
        verify(workRepository).insert(WORK_ID, LISTING_ID, 7, "UPSERT", NOW);
    }

    @Test
    void selfPublishedBusinessCreatesVersionedUpsertIntent() {
        ListingDraftResponse listing =
                listing("BUSINESS", "ACTIVE", "NOT_SUBMITTED", "BUSINESS_SELF_PUBLISHED", 4);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        when(ulidGenerator.next()).thenReturn(WORK_ID);

        service(true).recordCurrentState(LISTING_ID, NOW);

        verify(workRepository).insert(WORK_ID, LISTING_ID, 4, "UPSERT", NOW);
    }

    @Test
    void everyNonPublicLifecycleCreatesDeleteIntent() {
        String[] statuses = {
                "DRAFT", "PENDING_REVIEW", "PAUSED", "CLOSED",
                "CHANGES_REQUESTED", "REJECTED", "REMOVED_BY_ADMIN", "EXPIRED", "SUSPENDED"
        };
        when(ulidGenerator.next()).thenReturn(WORK_ID);
        ListingSearchProjectionIntentService service = service(true);

        for (int index = 0; index < statuses.length; index++) {
            int version = index + 1;
            ListingDraftResponse listing =
                    listing("INDIVIDUAL", statuses[index], "APPROVED", null, version);
            when(listingRepository.findOptionalById(LISTING_ID))
                    .thenReturn(Optional.of(listing));
            service.recordCurrentState(LISTING_ID, NOW);
            verify(workRepository).insert(WORK_ID, LISTING_ID, version, "DELETE", NOW);
        }
        verify(workRepository, never()).insert(WORK_ID, LISTING_ID, 99, "UPSERT", NOW);
    }

    private ListingSearchProjectionIntentService service(boolean enabled) {
        return new ListingSearchProjectionIntentService(
                new ListingSearchProjectionSyncProperties(
                        enabled, 50, 5000, 60, 20, Duration.ofSeconds(2), Duration.ofMinutes(5)),
                new ListingSearchPromotionProperties(false, false, 5, 10_000),
                listingRepository,
                workRepository,
                promotionRepository,
                ulidGenerator);
    }

    private ListingDraftResponse listing(
            String sellerType,
            String status,
            String moderationStatus,
            String publicationSource,
            long version) {
        ListingDraftResponse listing = mock(ListingDraftResponse.class);
        when(listing.id()).thenReturn(LISTING_ID);
        when(listing.sellerType()).thenReturn(sellerType);
        when(listing.status()).thenReturn(status);
        when(listing.moderationStatus()).thenReturn(moderationStatus);
        when(listing.publicationSource()).thenReturn(publicationSource);
        when(listing.version()).thenReturn(version);
        return listing;
    }
}
