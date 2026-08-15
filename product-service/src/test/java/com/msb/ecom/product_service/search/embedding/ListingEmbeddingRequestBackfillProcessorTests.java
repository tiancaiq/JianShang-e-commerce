package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingEmbeddingRequestBackfillProcessorTests {
    private static final Instant NOW = Instant.parse("2026-07-24T02:00:00Z");
    private static final String RUN_ID = "01R00000000000000000000901";
    private static final String LEASE_ID = "01L00000000000000000000901";
    private static final String LISTING_1 = "01D00000000000000000000901";
    private static final String LISTING_2 = "01D00000000000000000000902";
    private static final String LISTING_3 = "01D00000000000000000000903";

    private final ListingEmbeddingRequestBackfillRepository repository = mock(
            ListingEmbeddingRequestBackfillRepository.class);
    private final ListingDraftRepository listingRepository = mock(ListingDraftRepository.class);
    private final ListingEmbeddingRequestBackfillCandidateProcessor candidateProcessor = mock(
            ListingEmbeddingRequestBackfillCandidateProcessor.class);
    private final ListingDiscoveryEmbeddingMetrics metrics = mock(
            ListingDiscoveryEmbeddingMetrics.class);
    private final UlidGenerator ulids = mock(UlidGenerator.class);

    @Test
    void disabledOrRequestDisabledStopsBeforeCatalogAndRunWork() {
        assertThatThrownBy(() -> processor(false, true).start())
                .isInstanceOf(ListingEmbeddingRequestBackfillException.class)
                .extracting("kind")
                .isEqualTo(ListingEmbeddingRequestBackfillException.Kind.DISABLED);
        assertThatThrownBy(() -> processor(true, false).start())
                .isInstanceOf(ListingEmbeddingRequestBackfillException.class)
                .extracting("kind")
                .isEqualTo(ListingEmbeddingRequestBackfillException.Kind.UNAVAILABLE);

        verifyNoInteractions(repository, listingRepository, candidateProcessor);
    }

    @Test
    void startCapturesWatermarkAndProcessesOnlyOneStableBoundedPage() {
        ListingEmbeddingRequestBackfillRun pending = run("PENDING", 0, null);
        ListingEmbeddingRequestBackfillRun running = run("RUNNING", 0, null);
        ListingEmbeddingRequestBackfillRun afterPage = run("PENDING", 2, LISTING_2);
        when(repository.findActive()).thenReturn(Optional.empty());
        when(listingRepository.findMaximumEligibleIndividualListingId())
                .thenReturn(Optional.of(LISTING_3));
        when(ulids.next()).thenReturn(RUN_ID, LEASE_ID);
        when(repository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(pending), Optional.of(running), Optional.of(afterPage));
        when(repository.claim(anyString(), anyString(), any(), any())).thenReturn(true);
        when(listingRepository.findEligibleIndividualListingIdsForEmbeddingBackfill(
                null, LISTING_3, 3)).thenReturn(List.of(LISTING_1, LISTING_2, LISTING_3));
        when(candidateProcessor.process(LISTING_1))
                .thenReturn(ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.CREATED);
        when(candidateProcessor.process(LISTING_2))
                .thenReturn(ListingEmbeddingRequestBackfillCandidateProcessor.CandidateOutcome.ALREADY_PRESENT);
        when(repository.completePage(anyString(), anyString(), any(), any())).thenReturn(true);

        ListingEmbeddingRequestBackfillRun result = processor(true, true).start();

        assertThat(result).isEqualTo(afterPage);
        verify(repository).insert(RUN_ID, LISTING_3, NOW);
        verify(candidateProcessor).process(LISTING_1);
        verify(candidateProcessor).process(LISTING_2);
        verify(candidateProcessor, never()).process(LISTING_3);
    }

    @Test
    void candidateFailureLeavesCursorUnadvancedAndMarksRunRestartable() {
        ListingEmbeddingRequestBackfillRun pending = run("PENDING", 0, null);
        ListingEmbeddingRequestBackfillRun running = run("RUNNING", 0, null);
        when(repository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(pending), Optional.of(running));
        when(repository.claim(anyString(), anyString(), any(), any())).thenReturn(true);
        when(ulids.next()).thenReturn(LEASE_ID);
        when(listingRepository.findEligibleIndividualListingIdsForEmbeddingBackfill(
                null, LISTING_3, 3)).thenReturn(List.of(LISTING_1));
        when(candidateProcessor.process(LISTING_1))
                .thenThrow(new IllegalStateException("sensitive listing detail"));
        when(repository.fail(
                RUN_ID,
                LEASE_ID,
                "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE",
                NOW)).thenReturn(true);

        assertThatThrownBy(() -> processor(true, true).resume(RUN_ID))
                .isInstanceOf(ListingEmbeddingRequestBackfillException.class)
                .hasMessageNotContaining("sensitive listing detail");

        verify(repository).fail(
                RUN_ID,
                LEASE_ID,
                "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE",
                NOW);
        verify(repository, never()).completePage(anyString(), anyString(), any(), any());
    }

    @Test
    void completedResumeIsReplayWithZeroCatalogOrCandidateWork() {
        ListingEmbeddingRequestBackfillRun completed = run("COMPLETED", 2, LISTING_2);
        when(repository.findByRunId(RUN_ID)).thenReturn(Optional.of(completed));

        assertThat(processor(true, true).resume(RUN_ID)).isEqualTo(completed);

        verifyNoInteractions(listingRepository, candidateProcessor);
        verify(repository, never()).claim(anyString(), anyString(), any(), any());
    }

    @Test
    void emptyWatermarkCompletesOneEmptyPageWithoutCandidateWork() {
        ListingEmbeddingRequestBackfillRun pending = runWithUpperBound(
                "PENDING", 0, null, null);
        ListingEmbeddingRequestBackfillRun running = runWithUpperBound(
                "RUNNING", 0, null, null);
        ListingEmbeddingRequestBackfillRun completed = runWithUpperBound(
                "COMPLETED", 0, null, null);
        when(repository.findActive()).thenReturn(Optional.empty());
        when(listingRepository.findMaximumEligibleIndividualListingId())
                .thenReturn(Optional.empty());
        when(ulids.next()).thenReturn(RUN_ID, LEASE_ID);
        when(repository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(pending), Optional.of(running), Optional.of(completed));
        when(repository.claim(anyString(), anyString(), any(), any())).thenReturn(true);
        when(listingRepository.findEligibleIndividualListingIdsForEmbeddingBackfill(
                null, null, 3)).thenReturn(List.of());
        when(repository.completePage(anyString(), anyString(), any(), any())).thenReturn(true);

        assertThat(processor(true, true).start().state()).isEqualTo("COMPLETED");
        verifyNoInteractions(candidateProcessor);
    }

    @Test
    void configuredMaximumFailsClosedBeforeProcessingAnotherPage() {
        ListingEmbeddingRequestBackfillRun pending = new ListingEmbeddingRequestBackfillRun(
                RUN_ID, LISTING_3, LISTING_2, "PENDING",
                3, 5, 3, 2, 0, 0, null,
                null, null, NOW, NOW, null);
        ListingEmbeddingRequestBackfillRun running = new ListingEmbeddingRequestBackfillRun(
                RUN_ID, LISTING_3, LISTING_2, "RUNNING",
                3, 5, 3, 2, 0, 0, null,
                LEASE_ID, NOW.plusSeconds(120), NOW, NOW, null);
        when(repository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(pending), Optional.of(running));
        when(repository.claim(anyString(), anyString(), any(), any())).thenReturn(true);
        when(ulids.next()).thenReturn(LEASE_ID);
        when(repository.fail(
                RUN_ID,
                LEASE_ID,
                "LISTING_EMBEDDING_BACKFILL_BOUND_EXCEEDED",
                NOW)).thenReturn(true);

        assertThatThrownBy(() -> processor(true, true).resume(RUN_ID))
                .isInstanceOf(ListingEmbeddingRequestBackfillException.class)
                .extracting("code")
                .isEqualTo("LISTING_EMBEDDING_BACKFILL_BOUND_EXCEEDED");
        verifyNoInteractions(listingRepository, candidateProcessor);
    }

    private ListingEmbeddingRequestBackfillProcessor processor(
            boolean commandsEnabled,
            boolean requestEnabled) {
        return new ListingEmbeddingRequestBackfillProcessor(
                new ListingEmbeddingRequestBackfillProperties(
                        commandsEnabled, true, 2, 5, Duration.ofMinutes(2)),
                new ListingDiscoveryEmbeddingProperties(
                        requestEnabled, false, false, "listing-discovery-embedding-request-v1"),
                repository,
                listingRepository,
                candidateProcessor,
                metrics,
                ulids,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ListingEmbeddingRequestBackfillRun run(
            String state,
            int processedCount,
            String lastProcessed) {
        return runWithUpperBound(state, processedCount, lastProcessed, LISTING_3);
    }

    private ListingEmbeddingRequestBackfillRun runWithUpperBound(
            String state,
            int processedCount,
            String lastProcessed,
            String upperBound) {
        return new ListingEmbeddingRequestBackfillRun(
                RUN_ID,
                upperBound,
                lastProcessed,
                state,
                processedCount == 0 ? 0 : 1,
                processedCount,
                processedCount == 0 ? 0 : 1,
                processedCount == 0 ? 0 : 1,
                0,
                0,
                null,
                "RUNNING".equals(state) ? LEASE_ID : null,
                "RUNNING".equals(state) ? NOW.plusSeconds(120) : null,
                NOW,
                NOW,
                "COMPLETED".equals(state) ? NOW : null);
    }
}
