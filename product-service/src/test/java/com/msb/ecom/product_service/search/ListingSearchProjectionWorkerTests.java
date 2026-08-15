package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingSearchProjectionWorkerTests {

    private static final String LISTING_ID = "01L00000000000000000000241";
    private static final String WORK_ID = "01W00000000000000000000241";
    private static final String CLAIM_ID = "01C00000000000000000000241";
    private static final Instant CREATED_AT = Instant.parse("2026-07-23T09:00:00Z");

    private final ListingSearchProjectionWorkRepository workRepository =
            mock(ListingSearchProjectionWorkRepository.class);
    private final ListingDraftRepository listingRepository = mock(ListingDraftRepository.class);
    private final ListingMediaRepository mediaRepository = mock(ListingMediaRepository.class);
    private final OpenSearchListingSearchClient searchClient = mock(OpenSearchListingSearchClient.class);
    private final OpenSearchListingVectorBackfillClient vectorClient =
            mock(OpenSearchListingVectorBackfillClient.class);
    private final ListingSearchPromotionRepository promotionRepository =
            mock(ListingSearchPromotionRepository.class);
    private final ListingVectorProjectionDocumentFactory documentFactory =
            mock(ListingVectorProjectionDocumentFactory.class);
    private final ListingSearchProjectionMetrics metrics = mock(ListingSearchProjectionMetrics.class);
    private final UlidGenerator ulidGenerator = mock(UlidGenerator.class);

    @Test
    void currentPublicVersionIsUpsertedAndAcknowledged() {
        ListingSearchProjectionWork work = work("UPSERT", 0, 0);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "ACTIVE", "APPROVED", null, 0);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        when(listingRepository.findPublicListingById(LISTING_ID))
                .thenReturn(Optional.of(publicListing()));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());
        when(workRepository.markApplied(eq(WORK_ID), eq(CLAIM_ID), any(), eq("UPSERTED")))
                .thenReturn(true);

        worker(20).processPending();

        verify(searchClient).upsert(any(ListingSearchDocument.class), eq(1L));
        verify(workRepository).markApplied(eq(WORK_ID), eq(CLAIM_ID), any(), eq("UPSERTED"));
    }

    @Test
    void activeRebuildRequiresBothActiveAndCandidateWritesBeforeAcknowledgement() {
        ListingSearchProjectionWork work = work("UPSERT", 0, 0);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "ACTIVE", "APPROVED", null, 0);
        when(listingRepository.findOptionalById(LISTING_ID)).thenReturn(Optional.of(listing));
        when(listingRepository.findPublicListingById(LISTING_ID))
                .thenReturn(Optional.of(publicListing()));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());
        ListingSearchRebuildRun run = rebuildRun("CATCHING_UP");
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.of(run));
        ListingVectorBackfillDocument candidate = mock(ListingVectorBackfillDocument.class);
        when(documentFactory.lexical(eq(listing), any(), eq(List.of()))).thenReturn(candidate);
        org.mockito.Mockito.doThrow(new ListingSearchUnavailableException("candidate unavailable"))
                .when(vectorClient).upsertCandidate(run.candidateGeneration(), candidate);

        worker(20).processPending();

        verify(searchClient).upsert(any(ListingSearchDocument.class), eq(1L));
        verify(vectorClient).upsertCandidate(run.candidateGeneration(), candidate);
        verify(workRepository).markRetry(
                eq(WORK_ID), eq(CLAIM_ID), any(), eq("OPENSEARCH_UNAVAILABLE"));
        verify(workRepository, never()).markApplied(anyString(), anyString(), any(), anyString());
    }

    @Test
    void ineligibleCurrentVersionIsDeletedAndAcknowledged() {
        ListingSearchProjectionWork work = work("DELETE", 9, 0);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "CLOSED", "APPROVED", null, 9);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        when(workRepository.markApplied(eq(WORK_ID), eq(CLAIM_ID), any(), eq("DELETED")))
                .thenReturn(true);

        worker(20).processPending();

        verify(searchClient).delete(LISTING_ID, 19L);
        verify(listingRepository, never()).findPublicListingById(anyString());
    }

    @Test
    void zeroVersionIneligibleListingUsesTheFirstPositiveDeleteVersion() {
        ListingSearchProjectionWork work = work("DELETE", 0, 0);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "CLOSED", "APPROVED", null, 0);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        when(workRepository.markApplied(eq(WORK_ID), eq(CLAIM_ID), any(), eq("DELETED")))
                .thenReturn(true);

        worker(20).processPending();

        verify(searchClient).delete(LISTING_ID, 1L);
        verify(listingRepository, never()).findPublicListingById(anyString());
    }

    @Test
    void missingAuthoritativeRowExecutesDurableDeleteIntent() {
        ListingSearchProjectionWork work = work("DELETE", 10, 0);
        prepareBatch(work);
        when(listingRepository.findOptionalById(LISTING_ID)).thenReturn(Optional.empty());
        when(workRepository.markApplied(eq(WORK_ID), eq(CLAIM_ID), any(), eq("DELETED")))
                .thenReturn(true);

        worker(20).processPending();

        verify(searchClient).delete(LISTING_ID, 21L);
        verify(listingRepository, never()).findPublicListingById(anyString());
    }

    @Test
    void olderWorkIsSupersededWithoutOpenSearchCall() {
        ListingSearchProjectionWork work = work("UPSERT", 4, 0);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "CLOSED", "APPROVED", null, 5);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        when(workRepository.markApplied(eq(WORK_ID), eq(CLAIM_ID), any(), eq("SUPERSEDED")))
                .thenReturn(true);

        worker(20).processPending();

        verify(searchClient, never()).upsert(any(), eq(9L));
        verify(searchClient, never()).delete(anyString(), eq(9L));
    }

    @Test
    void outageReleasesClaimForBoundedRetry() {
        ListingSearchProjectionWork work = work("DELETE", 9, 1);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "CLOSED", "APPROVED", null, 9);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        org.mockito.Mockito.doThrow(new ListingSearchUnavailableException("offline"))
                .when(searchClient).delete(LISTING_ID, 19L);

        worker(20).processPending();

        verify(workRepository).markRetry(eq(WORK_ID), eq(CLAIM_ID), any(), eq("OPENSEARCH_UNAVAILABLE"));
        verify(workRepository, never()).markTerminal(anyString(), anyString(), any(), anyString());
    }

    @Test
    void exhaustedOutageBecomesSafeTerminalWork() {
        ListingSearchProjectionWork work = work("DELETE", 9, 2);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "CLOSED", "APPROVED", null, 9);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));
        org.mockito.Mockito.doThrow(new ListingSearchUnavailableException("offline"))
                .when(searchClient).delete(LISTING_ID, 19L);

        worker(3).processPending();

        verify(workRepository).markTerminal(
                eq(WORK_ID), eq(CLAIM_ID), any(), eq("OPENSEARCH_UNAVAILABLE"));
    }

    @Test
    void malformedOperationTerminatesWithoutProjectionWrite() {
        ListingSearchProjectionWork work = work("UPSERT", 9, 0);
        prepareBatch(work);
        ListingDraftResponse listing = listing("INDIVIDUAL", "CLOSED", "APPROVED", null, 9);
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing));

        worker(20).processPending();

        verify(workRepository).markTerminal(eq(WORK_ID), eq(CLAIM_ID), any(), eq("INVALID_WORK"));
        verify(searchClient, never()).upsert(any(), eq(19L));
        verify(searchClient, never()).delete(anyString(), eq(19L));
    }

    private ListingSearchProjectionWorker worker(int maxAttempts) {
        when(searchClient.enabled()).thenReturn(true);
        return new ListingSearchProjectionWorker(
                workRepository,
                listingRepository,
                mediaRepository,
                searchClient,
                vectorClient,
                promotionRepository,
                documentFactory,
                new ListingSearchProjectionSyncProperties(
                        true, 50, 5000, 60, maxAttempts, Duration.ofMillis(1), Duration.ofSeconds(1)),
                metrics,
                ulidGenerator);
    }

    private void prepareBatch(ListingSearchProjectionWork work) {
        when(ulidGenerator.next()).thenReturn(CLAIM_ID);
        when(workRepository.claimBatch(eq(CLAIM_ID), any(), any(), eq(50)))
                .thenReturn(List.of(work));
    }

    private ListingSearchProjectionWork work(String operation, long version, int attempts) {
        return new ListingSearchProjectionWork(
                WORK_ID, LISTING_ID, version, operation, attempts, CREATED_AT);
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

    private PublicListingResponse publicListing() {
        return new PublicListingResponse(
                LISTING_ID,
                "INDIVIDUAL",
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "01K00000000000000000000001",
                "furniture",
                "Furniture",
                "Desk",
                "Solid wood desk",
                "GOOD",
                null,
                new BigDecimal("42.00"),
                "USD",
                false,
                1,
                "Irvine",
                "Orange County",
                CREATED_AT,
                null,
                0,
                0,
                List.of());
    }

    private ListingSearchRebuildRun rebuildRun(String state) {
        return new ListingSearchRebuildRun(
                "01R00000000000000000000241",
                "marketplace-listings-v2-g20260723123456789",
                "marketplace-listings-v1",
                "marketplace-listings-v1",
                0,
                0L,
                state,
                0,
                0,
                0,
                0,
                null,
                CREATED_AT,
                CREATED_AT,
                null);
    }
}
