package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingSearchPromotionServiceTests {

    private static final String RUN_ID = "01R00000000000000000000601";
    private static final String PREVIOUS = "marketplace-listings-v1";
    private static final String CANDIDATE = "marketplace-listings-v2-g20260723123456789";
    private static final Instant NOW = Instant.parse("2026-07-23T12:34:56Z");

    private final ListingSearchPromotionRepository repository =
            mock(ListingSearchPromotionRepository.class);
    private final ListingVectorBackfillSnapshotReader snapshotReader =
            mock(ListingVectorBackfillSnapshotReader.class);
    private final OpenSearchListingVectorBackfillClient vectorClient =
            mock(OpenSearchListingVectorBackfillClient.class);
    private final ListingDraftRepository listings = mock(ListingDraftRepository.class);
    private final ListingMediaRepository media = mock(ListingMediaRepository.class);
    private final ListingVectorProjectionDocumentFactory documentFactory =
            mock(ListingVectorProjectionDocumentFactory.class);
    private final ListingSearchProjectionMetrics metrics =
            mock(ListingSearchProjectionMetrics.class);
    private final UlidGenerator ulids = mock(UlidGenerator.class);

    @Test
    void defaultOffStopsBeforeRepositoryOrOpenSearch() {
        ListingSearchPromotionService service = service(false, false);

        assertThatThrownBy(service::prepareInactiveCandidate)
                .isInstanceOf(ListingSearchUnavailableException.class);
        assertThat(service.commandEligibility(run("CATCHING_UP")))
                .isEqualTo(ListingSearchPromotionEligibility.NONE);

        verifyNoInteractions(repository, snapshotReader, vectorClient, listings, media);
    }

    @Test
    void promotionHoldsExclusiveFenceDrainsBoundaryAndMovesBothAliases() {
        ListingSearchRebuildRun run = run("CATCHING_UP");
        when(vectorClient.enabled()).thenReturn(true);
        when(repository.lockById(RUN_ID)).thenReturn(Optional.of(run));
        when(repository.unresolvedWorkCount()).thenReturn(0L);
        when(vectorClient.stableAliasState())
                .thenReturn(new OpenSearchListingVectorBackfillClient.AliasState(
                        PREVIOUS, PREVIOUS));
        when(repository.workAfter(5, 101)).thenReturn(List.of());
        when(snapshotReader.read(any(), any()))
                .thenReturn(new ListingVectorBackfillSnapshotReader.Snapshot(List.of(), 0));
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run("PROMOTED")));

        service(true, true).promote(RUN_ID);

        verify(repository).acquirePromotionFenceExclusive(5);
        verify(repository).markPromotionFenced(org.mockito.ArgumentMatchers.eq(RUN_ID), any(Instant.class));
        verify(vectorClient).validateCandidate(CANDIDATE, 0, 0);
        verify(vectorClient).requireCandidateUnaliased(CANDIDATE);
        verify(vectorClient).promoteAliases(PREVIOUS, CANDIDATE);
        verify(repository).markPromoted(org.mockito.ArgumentMatchers.eq(RUN_ID), any(Instant.class));
    }

    @Test
    void registersDualWriteBeforeBoundedBackfillAndCatchUp() {
        ListingSearchRebuildRun registered = run("BACKFILLING");
        ListingSearchRebuildRun catchingUp = run("CATCHING_UP");
        when(vectorClient.enabled()).thenReturn(true);
        when(vectorClient.stableAliasState())
                .thenReturn(new OpenSearchListingVectorBackfillClient.AliasState(
                        PREVIOUS, PREVIOUS));
        when(vectorClient.allocateGenerationName()).thenReturn(CANDIDATE);
        when(ulids.next()).thenReturn(RUN_ID);
        when(repository.currentWorkSequence()).thenReturn(5L);
        when(repository.currentReceiptSequence()).thenReturn(7L);
        when(repository.findById(RUN_ID))
                .thenReturn(Optional.of(registered), Optional.of(catchingUp), Optional.of(catchingUp));
        when(repository.workAfter(5, 101)).thenReturn(List.of());
        var empty = new ListingVectorBackfillSnapshotReader.Snapshot(List.of(), 0);
        when(snapshotReader.read(any(), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(empty);

        service(true, false).prepareInactiveCandidate();

        var order = inOrder(repository, vectorClient, snapshotReader);
        order.verify(repository).insertPreparingRun(
                org.mockito.ArgumentMatchers.eq(RUN_ID),
                org.mockito.ArgumentMatchers.eq(CANDIDATE),
                org.mockito.ArgumentMatchers.eq(PREVIOUS),
                org.mockito.ArgumentMatchers.eq(PREVIOUS),
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.any());
        order.verify(vectorClient).createInactiveGeneration(CANDIDATE);
        order.verify(repository).activateDualWrite(
                org.mockito.ArgumentMatchers.eq(RUN_ID),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any());
        order.verify(snapshotReader).read(any(), org.mockito.ArgumentMatchers.eq(7L));
        verify(vectorClient).backfillRegisteredGeneration(CANDIDATE, List.of());
        verify(repository).recordCatchUp(
                org.mockito.ArgumentMatchers.eq(RUN_ID),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recoveryMarksCandidateAliasOutcomePromotedWithoutBlindAliasReplay() {
        ListingSearchRebuildRun run = run("PROMOTION_FENCED");
        when(vectorClient.enabled()).thenReturn(true);
        when(repository.lockById(RUN_ID)).thenReturn(Optional.of(run));
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run("PROMOTED")));
        when(vectorClient.stableAliasState())
                .thenReturn(new OpenSearchListingVectorBackfillClient.AliasState(
                        CANDIDATE, CANDIDATE));

        service(true, true).recover(RUN_ID);

        verify(repository).markRecoveredPromoted(
                org.mockito.ArgumentMatchers.eq(RUN_ID), any(Instant.class));
        verify(vectorClient, never()).promoteAliases(any(), any());
        verify(vectorClient, never()).rollbackAliases(any(), any());
    }

    @Test
    void eligibilityUsesExactAliasesAndUnresolvedWorkWithoutTreatingCountsAsLag() {
        ListingSearchRebuildRun run = run("CATCHING_UP");
        when(vectorClient.enabled()).thenReturn(true);
        when(vectorClient.stableAliasState())
                .thenReturn(new OpenSearchListingVectorBackfillClient.AliasState(
                        PREVIOUS, PREVIOUS));
        when(repository.unresolvedWorkCount()).thenReturn(1L, 0L);
        ListingSearchPromotionService service = service(true, true);

        ListingSearchPromotionEligibility withLag = service.commandEligibility(run);
        ListingSearchPromotionEligibility drained = service.commandEligibility(run);

        assertThat(withLag).isEqualTo(new ListingSearchPromotionEligibility(
                true, false, false));
        assertThat(drained).isEqualTo(new ListingSearchPromotionEligibility(
                true, true, false));
    }

    @Test
    void eligibilityInvitesRecoveryOnlyForUnknownOrRecoverableAliasOutcomes() {
        ListingSearchPromotionService service = service(true, true);
        when(vectorClient.enabled()).thenReturn(true);
        when(repository.unresolvedWorkCount()).thenReturn(0L);
        when(vectorClient.stableAliasState())
                .thenReturn(
                        new OpenSearchListingVectorBackfillClient.AliasState(
                                CANDIDATE, CANDIDATE),
                        new OpenSearchListingVectorBackfillClient.AliasState(
                                PREVIOUS, PREVIOUS));

        assertThat(service.commandEligibility(run("CATCHING_UP")).canRecover())
                .isTrue();
        assertThat(service.commandEligibility(run("ROLLBACK_REQUIRED")).canRecover())
                .isTrue();
        assertThat(service.commandEligibility(run("PROMOTED")))
                .isEqualTo(ListingSearchPromotionEligibility.NONE);
    }

    @Test
    void rollbackRequiredRecoveryReturnsToCatchingUpWhenPreviousAliasesAreHealthy() {
        ListingSearchRebuildRun run = run("ROLLBACK_REQUIRED");
        when(vectorClient.enabled()).thenReturn(true);
        when(repository.lockById(RUN_ID)).thenReturn(Optional.of(run));
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run("CATCHING_UP")));
        when(vectorClient.stableAliasState())
                .thenReturn(new OpenSearchListingVectorBackfillClient.AliasState(
                        PREVIOUS, PREVIOUS));

        ListingSearchRebuildRun recovered = service(true, true).recover(RUN_ID);

        assertThat(recovered.state()).isEqualTo("CATCHING_UP");
        verify(repository).restoreCatchingUp(
                org.mockito.ArgumentMatchers.eq(RUN_ID), any(Instant.class));
        verify(vectorClient, never()).promoteAliases(any(), any());
    }

    @Test
    void unresolvedWorkStopsBeforeAliasMutation() {
        ListingSearchRebuildRun run = run("CATCHING_UP");
        when(vectorClient.enabled()).thenReturn(true);
        when(repository.lockById(RUN_ID)).thenReturn(Optional.of(run));
        when(repository.unresolvedWorkCount()).thenReturn(1L);
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(vectorClient.stableAliasState())
                .thenReturn(new OpenSearchListingVectorBackfillClient.AliasState(
                        PREVIOUS, PREVIOUS));

        assertThatThrownBy(() -> service(true, true).promote(RUN_ID))
                .isInstanceOf(ListingSearchUnavailableException.class);

        verify(vectorClient, never()).promoteAliases(any(), any());
    }

    @Test
    void promotionRechecksEligibilityUnderFenceAfterAReadyStatusSnapshot() {
        ListingSearchRebuildRun run = run("CATCHING_UP");
        when(vectorClient.enabled()).thenReturn(true);
        when(vectorClient.stableAliasState())
                .thenReturn(new OpenSearchListingVectorBackfillClient.AliasState(
                        PREVIOUS, PREVIOUS));
        when(repository.unresolvedWorkCount()).thenReturn(0L, 1L);
        when(repository.lockById(RUN_ID)).thenReturn(Optional.of(run));
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(run));
        ListingSearchPromotionService service = service(true, true);

        assertThat(service.commandEligibility(run).canPromote()).isTrue();
        assertThatThrownBy(() -> service.promote(RUN_ID))
                .isInstanceOf(ListingSearchUnavailableException.class);

        verify(repository).acquirePromotionFenceExclusive(5);
        verify(vectorClient, never()).promoteAliases(any(), any());
    }

    @Test
    void concurrentPromotionReplayObservesDurableSuccessWithoutMovingAliasesAgain() {
        ListingSearchRebuildRun promoted = run("PROMOTED");
        when(vectorClient.enabled()).thenReturn(true);
        when(repository.lockById(RUN_ID)).thenReturn(Optional.of(promoted));
        when(repository.findById(RUN_ID)).thenReturn(Optional.of(promoted));

        ListingSearchRebuildRun replay = service(true, true).promote(RUN_ID);

        assertThat(replay.state()).isEqualTo("PROMOTED");
        verify(repository).acquirePromotionFenceExclusive(5);
        verify(repository, never()).markPromotionFenced(any(), any());
        verify(vectorClient, never()).promoteAliases(any(), any());
        verify(vectorClient, never()).rollbackAliases(any(), any());
    }

    private ListingSearchPromotionService service(boolean rebuild, boolean promote) {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        return new ListingSearchPromotionService(
                new ListingSearchPromotionProperties(rebuild, promote, 5, 100),
                new ListingSearchProjectionSyncProperties(
                        true, 50, 5000, 60, 20, Duration.ofSeconds(1), Duration.ofMinutes(1)),
                new ListingVectorBackfillProperties(
                        true, "marketplace-listings-v2", 50, 1000, 5_000_000, false),
                repository,
                snapshotReader,
                vectorClient,
                listings,
                media,
                documentFactory,
                metrics,
                ulids,
                transactionManager);
    }

    private ListingSearchRebuildRun run(String state) {
        return new ListingSearchRebuildRun(
                RUN_ID,
                CANDIDATE,
                PREVIOUS,
                PREVIOUS,
                5,
                7L,
                state,
                0,
                0,
                0,
                0,
                null,
                NOW,
                NOW,
                "PROMOTED".equals(state) ? NOW : null);
    }
}
