package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ListingSearchPromotionService {

    private final ListingSearchPromotionProperties promotionProperties;
    private final ListingSearchProjectionSyncProperties syncProperties;
    private final ListingVectorBackfillProperties backfillProperties;
    private final ListingSearchPromotionRepository promotionRepository;
    private final ListingVectorBackfillSnapshotReader snapshotReader;
    private final OpenSearchListingVectorBackfillClient vectorClient;
    private final ListingDraftRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final ListingVectorProjectionDocumentFactory documentFactory;
    private final ListingSearchProjectionMetrics metrics;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate requiresNew;

    @Autowired
    public ListingSearchPromotionService(
            ListingSearchPromotionProperties promotionProperties,
            ListingSearchProjectionSyncProperties syncProperties,
            ListingVectorBackfillProperties backfillProperties,
            ListingSearchPromotionRepository promotionRepository,
            ListingVectorBackfillSnapshotReader snapshotReader,
            OpenSearchListingVectorBackfillClient vectorClient,
            ListingDraftRepository listingRepository,
            ListingMediaRepository mediaRepository,
            ListingVectorProjectionDocumentFactory documentFactory,
            ListingSearchProjectionMetrics metrics,
            UlidGenerator ulidGenerator,
            PlatformTransactionManager transactionManager) {
        this(
                promotionProperties,
                syncProperties,
                backfillProperties,
                promotionRepository,
                snapshotReader,
                vectorClient,
                listingRepository,
                mediaRepository,
                documentFactory,
                metrics,
                ulidGenerator,
                Clock.systemUTC(),
                template(transactionManager, TransactionDefinition.PROPAGATION_REQUIRED),
                template(transactionManager, TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    ListingSearchPromotionService(
            ListingSearchPromotionProperties promotionProperties,
            ListingSearchProjectionSyncProperties syncProperties,
            ListingVectorBackfillProperties backfillProperties,
            ListingSearchPromotionRepository promotionRepository,
            ListingVectorBackfillSnapshotReader snapshotReader,
            OpenSearchListingVectorBackfillClient vectorClient,
            ListingDraftRepository listingRepository,
            ListingMediaRepository mediaRepository,
            ListingVectorProjectionDocumentFactory documentFactory,
            ListingSearchProjectionMetrics metrics,
            UlidGenerator ulidGenerator,
            Clock clock,
            TransactionTemplate transaction,
            TransactionTemplate requiresNew) {
        this.promotionProperties = promotionProperties;
        this.syncProperties = syncProperties;
        this.backfillProperties = backfillProperties;
        this.promotionRepository = promotionRepository;
        this.snapshotReader = snapshotReader;
        this.vectorClient = vectorClient;
        this.listingRepository = listingRepository;
        this.mediaRepository = mediaRepository;
        this.documentFactory = documentFactory;
        this.metrics = metrics;
        this.ulidGenerator = ulidGenerator;
        this.clock = clock;
        this.transaction = transaction;
        this.requiresNew = requiresNew;
    }

    // Registers dual-write before snapshotting, then backfills and catches up an inactive candidate.
    public ListingSearchRebuildRun prepareInactiveCandidate() {
        requireRebuildEnabled();
        OpenSearchListingVectorBackfillClient.AliasState aliases = vectorClient.stableAliasState();
        if (!aliases.readGeneration().equals(aliases.writeGeneration())) {
            throw new ListingSearchUnavailableException(
                    "Listing search stable aliases do not share one generation.");
        }
        String candidate = vectorClient.allocateGenerationName();
        String runId = ulidGenerator.next();
        transaction.executeWithoutResult(status -> {
            promotionRepository.acquirePromotionFenceExclusive(
                    promotionProperties.effectiveLockWaitSeconds());
            if (promotionRepository.findActiveRun().isPresent()) {
                throw new ListingSearchUnavailableException(
                        "A listing search rebuild is already active.");
            }
            promotionRepository.insertPreparingRun(
                    runId,
                    candidate,
                    aliases.readGeneration(),
                    aliases.writeGeneration(),
                    promotionRepository.currentWorkSequence(),
                    clock.instant());
        });

        try {
            vectorClient.createInactiveGeneration(candidate);
            transaction.executeWithoutResult(status -> promotionRepository.activateDualWrite(
                    runId,
                    promotionRepository.currentReceiptSequence(),
                    clock.instant()));
            transaction.executeWithoutResult(
                    status -> promotionRepository.startBackfill(runId, clock.instant()));
            ListingSearchRebuildRun registered = promotionRepository.findById(runId)
                    .orElseThrow(() -> new ListingSearchUnavailableException(
                            "Listing search rebuild registration is unavailable."));
            ListingVectorBackfillSnapshotReader.Snapshot snapshot =
                    snapshotReader.read(backfillProperties, registered.receiptWatermarkSequence());
            vectorClient.backfillRegisteredGeneration(candidate, snapshot.documents());
            int vectors = Math.toIntExact(snapshot.documents().stream()
                    .filter(ListingVectorBackfillDocument::hasEmbedding)
                    .count());
            transaction.executeWithoutResult(status -> promotionRepository.recordBackfill(
                    runId,
                    snapshot.documents().size(),
                    vectors,
                    snapshot.documents().size() - vectors,
                    clock.instant()));
            catchUp(runId);
            validateCandidateAgainstSnapshot(
                    candidate,
                    snapshotReader.read(
                            backfillProperties,
                            registered.receiptWatermarkSequence()));
            metrics.record("vector_promotion_prepare", "inactive_validated");
            return promotionRepository.findById(runId).orElseThrow();
        } catch (RuntimeException exception) {
            failRun(runId, "PREPARATION_FAILED");
            metrics.record("vector_promotion_prepare", "failure");
            throw exception;
        }
    }

    // Replays every durable post-registration intent by re-reading current Product truth.
    public int catchUp(String runId) {
        requireRebuildEnabled();
        ListingSearchRebuildRun run = promotionRepository.findById(runId)
                .orElseThrow(() -> new ListingSearchUnavailableException(
                        "Listing search rebuild was not found."));
        if (!commandEligibility(run).canCatchUp()) {
            throw new ListingSearchUnavailableException(
                    "Listing search rebuild is not ready for catch-up.");
        }
        List<ListingSearchPromotionRepository.ProjectionWorkBoundary> work =
                promotionRepository.workAfter(
                        run.startWorkSequence(),
                        promotionProperties.effectiveMaxCatchUpWork() + 1);
        if (work.size() > promotionProperties.effectiveMaxCatchUpWork()) {
            throw new ListingSearchUnavailableException(
                    "Listing search rebuild catch-up exceeds its safe bound.");
        }
        for (ListingSearchPromotionRepository.ProjectionWorkBoundary item : work) {
            applyCurrentState(run.candidateGeneration(), item.listingId(), item.listingVersion());
        }
        transaction.executeWithoutResult(status -> promotionRepository.recordCatchUp(
                runId, work.size(), clock.instant()));
        return work.size();
    }

    // Holds the exclusive Product fence from the final drain through the one-request alias move.
    public ListingSearchRebuildRun promote(String runId) {
        requirePromotionEnabled();
        AtomicBoolean replayed = new AtomicBoolean(false);
        try {
            transaction.executeWithoutResult(status -> {
                promotionRepository.acquirePromotionFenceExclusive(
                        promotionProperties.effectiveLockWaitSeconds());
                ListingSearchRebuildRun run = promotionRepository.lockById(runId)
                        .orElseThrow(() -> new ListingSearchUnavailableException(
                                "Listing search rebuild was not found."));
                if ("PROMOTED".equals(run.state())) {
                    replayed.set(true);
                    return;
                }
                if (!commandEligibility(run).canPromote()) {
                    throw new ListingSearchUnavailableException(
                            "Listing search rebuild is not promotable.");
                }
                promotionRepository.markPromotionFenced(runId, clock.instant());
                replayBoundary(run);
                ListingVectorBackfillSnapshotReader.Snapshot exact =
                        snapshotReader.read(
                                backfillProperties,
                                run.receiptWatermarkSequence());
                validateCandidateAgainstSnapshot(run.candidateGeneration(), exact);
                vectorClient.requireCandidateUnaliased(run.candidateGeneration());
                vectorClient.promoteAliases(
                        run.previousReadGeneration(),
                        run.candidateGeneration());
                promotionRepository.markPromoted(runId, clock.instant());
            });
            metrics.record("vector_promotion", replayed.get() ? "replayed" : "promoted");
            return promotionRepository.findById(runId).orElseThrow();
        } catch (RuntimeException exception) {
            reconcileFailedPromotion(runId);
            metrics.record("vector_promotion", "failure");
            throw exception;
        }
    }

    // Reconciles a crash/unknown outcome from durable run state against exact alias state.
    public ListingSearchRebuildRun recover(String runId) {
        requirePromotionEnabled();
        transaction.executeWithoutResult(status -> {
            promotionRepository.acquirePromotionFenceExclusive(
                    promotionProperties.effectiveLockWaitSeconds());
            ListingSearchRebuildRun run = promotionRepository.lockById(runId)
                    .orElseThrow(() -> new ListingSearchUnavailableException(
                            "Listing search rebuild was not found."));
            OpenSearchListingVectorBackfillClient.AliasState aliases =
                    vectorClient.stableAliasState();
            ListingSearchPromotionEligibility eligibility =
                    commandEligibility(run, aliases, promotionRepository.unresolvedWorkCount());
            if (!eligibility.canRecover()) {
                throw new ListingSearchUnavailableException(
                        "Listing search rebuild does not require recovery.");
            }
            if (run.candidateGeneration().equals(aliases.readGeneration())
                    && run.candidateGeneration().equals(aliases.writeGeneration())) {
                promotionRepository.markRecoveredPromoted(runId, clock.instant());
                return;
            }
            if (run.previousReadGeneration().equals(aliases.readGeneration())
                    && run.previousWriteGeneration().equals(aliases.writeGeneration())) {
                promotionRepository.restoreCatchingUp(runId, clock.instant());
                return;
            }
            throw new ListingSearchUnavailableException(
                    "Listing search aliases require operator reconciliation.");
        });
        return promotionRepository.findById(runId).orElseThrow();
    }

    // Supplies the operator boundary with one safe durable run without touching OpenSearch.
    public Optional<ListingSearchRebuildRun> findRun(String runId) {
        return promotionRepository.findById(runId);
    }

    // Allows the operator boundary to reject duplicate prepare before allocating a generation.
    public Optional<ListingSearchRebuildRun> findActiveRun() {
        return promotionRepository.findActiveRun();
    }

    // Derives operator command eligibility from the same alias and durable-work preconditions.
    public ListingSearchPromotionEligibility commandEligibility(ListingSearchRebuildRun run) {
        boolean rebuildEnabled = promotionProperties.rebuildEnabled()
                && syncProperties.enabled()
                && backfillProperties.enabled()
                && vectorClient.enabled();
        if (!rebuildEnabled || !requiresAliasInspection(run.state())) {
            return ListingSearchPromotionEligibility.NONE;
        }
        ListingSearchPromotionEligibility eligibility = commandEligibility(
                run,
                vectorClient.stableAliasState(),
                promotionRepository.unresolvedWorkCount());
        if (promotionProperties.promotionEnabled()) {
            return eligibility;
        }
        return new ListingSearchPromotionEligibility(
                eligibility.canCatchUp(),
                false,
                false);
    }

    private void replayBoundary(ListingSearchRebuildRun run) {
        List<ListingSearchPromotionRepository.ProjectionWorkBoundary> work =
                promotionRepository.workAfter(
                        run.startWorkSequence(),
                        promotionProperties.effectiveMaxCatchUpWork() + 1);
        if (work.size() > promotionProperties.effectiveMaxCatchUpWork()) {
            throw new ListingSearchUnavailableException(
                    "Listing search promotion boundary exceeds its safe bound.");
        }
        for (ListingSearchPromotionRepository.ProjectionWorkBoundary item : work) {
            applyCurrentState(run.candidateGeneration(), item.listingId(), item.listingVersion());
        }
    }

    private ListingSearchPromotionEligibility commandEligibility(
            ListingSearchRebuildRun run,
            OpenSearchListingVectorBackfillClient.AliasState aliases,
            long unresolvedWorkCount) {
        boolean aliasesOnPrevious =
                run.previousReadGeneration().equals(aliases.readGeneration())
                        && run.previousWriteGeneration().equals(aliases.writeGeneration());
        boolean aliasesOnCandidate =
                run.candidateGeneration().equals(aliases.readGeneration())
                        && run.candidateGeneration().equals(aliases.writeGeneration());
        boolean catchingUp = "CATCHING_UP".equals(run.state());
        boolean canCatchUp = catchingUp && aliasesOnPrevious;
        boolean canPromote = canCatchUp && unresolvedWorkCount == 0;
        boolean recoverableState = "PROMOTION_FENCED".equals(run.state())
                || "ROLLBACK_REQUIRED".equals(run.state());
        boolean unknownPromotedOutcome = catchingUp && aliasesOnCandidate;
        boolean canRecover = unknownPromotedOutcome
                || (recoverableState && (aliasesOnPrevious || aliasesOnCandidate));
        return new ListingSearchPromotionEligibility(
                canCatchUp,
                canPromote,
                canRecover);
    }

    private boolean requiresAliasInspection(String state) {
        return "CATCHING_UP".equals(state)
                || "PROMOTION_FENCED".equals(state)
                || "ROLLBACK_REQUIRED".equals(state);
    }

    private void applyCurrentState(
            String candidate,
            String listingId,
            long intentVersion) {
        Optional<ListingDraftResponse> current = listingRepository.findOptionalById(listingId);
        if (current.filter(ListingSearchProjectionIntentService::isPubliclySearchable).isPresent()) {
            ListingDraftResponse listing = current.orElseThrow();
            PublicListingResponse publicListing = listingRepository.findPublicListingById(listingId)
                    .orElseThrow(() -> new ListingSearchUnavailableException(
                            "Public listing projection row is unavailable."));
            var images = mediaRepository.findPublicImagesByListingId(listingId);
            vectorClient.upsertCandidate(
                    candidate,
                    documentFactory.lexical(listing, publicListing, images));
            return;
        }
        long deleteVersion = current.map(ListingDraftResponse::version).orElse(intentVersion);
        vectorClient.deleteCandidate(candidate, listingId, deleteVersion);
    }

    private void validateCandidateAgainstSnapshot(
            String generation,
            ListingVectorBackfillSnapshotReader.Snapshot snapshot) {
        int vectorCount = Math.toIntExact(snapshot.documents().stream()
                .filter(ListingVectorBackfillDocument::hasEmbedding)
                .count());
        vectorClient.validateCandidate(
                generation,
                snapshot.documents().size(),
                vectorCount);
    }

    private void reconcileFailedPromotion(String runId) {
        try {
            ListingSearchRebuildRun run = promotionRepository.findById(runId).orElse(null);
            if (run == null || "PROMOTED".equals(run.state())) {
                return;
            }
            OpenSearchListingVectorBackfillClient.AliasState aliases =
                    vectorClient.stableAliasState();
            if (run.candidateGeneration().equals(aliases.readGeneration())
                    && run.candidateGeneration().equals(aliases.writeGeneration())) {
                vectorClient.rollbackAliases(
                        run.candidateGeneration(),
                        run.previousReadGeneration());
            }
            requiresNew.executeWithoutResult(status -> {
                ListingSearchRebuildRun current =
                        promotionRepository.lockById(runId).orElseThrow();
                String expected = current.state();
                String target = "PROMOTION_FENCED".equals(expected)
                        ? "CATCHING_UP"
                        : "FAILED";
                if ("PROMOTION_FENCED".equals(expected)) {
                    promotionRepository.restoreCatchingUp(runId, clock.instant());
                } else if (!"PROMOTED".equals(expected)) {
                    promotionRepository.markFailed(
                            runId, expected, target, "PROMOTION_FAILED", clock.instant());
                }
            });
        } catch (RuntimeException recoveryFailure) {
            ListingSearchRebuildRun current = promotionRepository.findById(runId).orElse(null);
            if (current != null && !"PROMOTED".equals(current.state())) {
                try {
                    requiresNew.executeWithoutResult(status -> promotionRepository.markFailed(
                            runId,
                            current.state(),
                            "ROLLBACK_REQUIRED",
                            "ALIAS_OUTCOME_UNKNOWN",
                            clock.instant()));
                } catch (RuntimeException ignored) {
                    // Durable state and exact aliases remain available for explicit recovery.
                }
            }
        }
    }

    private void failRun(String runId, String errorCode) {
        try {
            ListingSearchRebuildRun current = promotionRepository.findById(runId).orElse(null);
            if (current != null && !"PROMOTED".equals(current.state())) {
                requiresNew.executeWithoutResult(status -> promotionRepository.markFailed(
                        runId, current.state(), "FAILED", errorCode, clock.instant()));
            }
        } catch (RuntimeException ignored) {
            // The original bounded failure is returned; recovery can inspect the durable run.
        }
    }

    private void requireRebuildEnabled() {
        if (!promotionProperties.rebuildEnabled()
                || !syncProperties.enabled()
                || !backfillProperties.enabled()
                || !vectorClient.enabled()) {
            throw new ListingSearchUnavailableException(
                    "Listing search rebuild is disabled.");
        }
    }

    private void requirePromotionEnabled() {
        requireRebuildEnabled();
        if (!promotionProperties.promotionEnabled()) {
            throw new ListingSearchUnavailableException(
                    "Listing search promotion is disabled.");
        }
    }

    private static TransactionTemplate template(
            PlatformTransactionManager transactionManager,
            int propagation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        template.setTimeout(30);
        return template;
    }
}
