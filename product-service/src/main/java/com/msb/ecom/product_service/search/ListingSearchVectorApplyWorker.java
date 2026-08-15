package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        name = "listing.search.vector-sync.worker-enabled",
        havingValue = "true")
@Slf4j
public class ListingSearchVectorApplyWorker {

    private final ListingSearchVectorSyncProperties properties;
    private final ListingSearchVectorApplyWorkRepository workRepository;
    private final ListingSearchVectorDocumentResolver documentResolver;
    private final ListingSearchPromotionRepository promotionRepository;
    private final ListingSearchPromotionProperties promotionProperties;
    private final OpenSearchListingVectorBackfillClient vectorClient;
    private final ListingSearchVectorSyncMetrics metrics;
    private final UlidGenerator ulidGenerator;
    private final TransactionTemplate transaction;

    public ListingSearchVectorApplyWorker(
            ListingSearchVectorSyncProperties properties,
            ListingSearchVectorApplyWorkRepository workRepository,
            ListingSearchVectorDocumentResolver documentResolver,
            ListingSearchPromotionRepository promotionRepository,
            ListingSearchPromotionProperties promotionProperties,
            OpenSearchListingVectorBackfillClient vectorClient,
            ListingSearchVectorSyncMetrics metrics,
            UlidGenerator ulidGenerator,
            PlatformTransactionManager transactionManager) {
        if (!properties.enabled() || !vectorClient.vectorWritesAvailable()) {
            throw new IllegalStateException(
                    "Listing vector worker requires vector sync and V2 OpenSearch support.");
        }
        this.properties = properties;
        this.workRepository = workRepository;
        this.documentResolver = documentResolver;
        this.promotionRepository = promotionRepository;
        this.promotionProperties = promotionProperties;
        this.vectorClient = vectorClient;
        this.metrics = metrics;
        this.ulidGenerator = ulidGenerator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(30);
        metrics.registerPendingGauge(workRepository);
    }

    @Scheduled(fixedDelayString = "${listing.search.vector-sync.poll-interval-ms:5000}")
    // Claims only after a compatible V2 target exists, leaving V1-only operation untouched.
    public void processPending() {
        if (!probeTargets().ready()) {
            metrics.record("apply", "target_not_ready");
            return;
        }
        Instant now = Instant.now();
        String claimToken = ulidGenerator.next();
        List<ListingSearchVectorApplyWork> work = workRepository.claimBatch(
                claimToken,
                now,
                now.plusSeconds(properties.effectiveClaimSeconds()),
                properties.effectiveBatchSize());
        for (ListingSearchVectorApplyWork item : work) {
            if (Thread.currentThread().isInterrupted()) {
                metrics.record("apply", "cancelled");
                return;
            }
            process(item, claimToken);
        }
    }

    private void process(ListingSearchVectorApplyWork work, String claimToken) {
        transaction.executeWithoutResult(status -> {
            promotionRepository.acquireMutationFenceShared(
                    promotionProperties.effectiveLockWaitSeconds());
            try {
                ListingVectorBackfillDocument document = documentResolver.resolve(
                        work.requestId(),
                        work.listingId(),
                        work.listingVersion());
                OpenSearchListingVectorBackfillClient.VectorTargets targets = resolveTargets();
                if (!targets.ready()) {
                    retryOrExhaust(work, claimToken, "TARGET_NOT_READY", "target_not_ready");
                    return;
                }
                for (String target : targets.generations()) {
                    vectorClient.upsertVectorTarget(target, document);
                }
                if (workRepository.markApplied(work.workId(), claimToken, Instant.now())) {
                    metrics.record("apply", "applied");
                } else {
                    metrics.record("apply", "lost_claim");
                }
            } catch (ListingSearchVectorStaleException exception) {
                workRepository.markStale(
                        work.workId(), claimToken, Instant.now(), "STALE");
                metrics.record("apply", "stale");
            } catch (ListingSearchVectorMalformedReceiptException exception) {
                workRepository.markTerminal(
                        work.workId(), claimToken, Instant.now(), "MALFORMED_RECEIPT");
                metrics.record("apply", "malformed_receipt");
            } catch (ListingSearchVectorTargetIncompatibleException exception) {
                retryOrExhaust(
                        work, claimToken, "INCOMPATIBLE_TARGET", "incompatible_target");
            } catch (ListingSearchUnavailableException exception) {
                retryOrExhaust(
                        work, claimToken, "TARGET_UNAVAILABLE", "retryable_target_failure");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                workRepository.markTerminal(
                        work.workId(), claimToken, Instant.now(), "INVALID_WORK");
                metrics.record("apply", "exhausted");
                log.warn("Listing vector apply work rejected result=invalid_work");
            } catch (RuntimeException exception) {
                retryOrExhaust(
                        work, claimToken, "UNEXPECTED_FAILURE", "retryable_target_failure");
            }
        });
    }

    private OpenSearchListingVectorBackfillClient.VectorTargets probeTargets() {
        try {
            return resolveTargets();
        } catch (ListingSearchVectorTargetIncompatibleException exception) {
            metrics.record("apply", "incompatible_target");
            return new OpenSearchListingVectorBackfillClient.VectorTargets(List.of());
        } catch (ListingSearchUnavailableException exception) {
            metrics.record("apply", "retryable_target_failure");
            return new OpenSearchListingVectorBackfillClient.VectorTargets(List.of());
        }
    }

    private OpenSearchListingVectorBackfillClient.VectorTargets resolveTargets() {
        Optional<String> candidate = promotionRepository.findDualWriteRun()
                .map(ListingSearchRebuildRun::candidateGeneration);
        return vectorClient.resolveVectorTargets(candidate);
    }

    private void retryOrExhaust(
            ListingSearchVectorApplyWork work,
            String claimToken,
            String errorCode,
            String outcome) {
        int nextAttempt = work.attemptCount() + 1;
        if (nextAttempt >= properties.effectiveMaxAttempts()) {
            workRepository.markTerminal(
                    work.workId(), claimToken, Instant.now(), errorCode);
            metrics.record("apply", "exhausted");
            log.warn("Listing vector apply work exhausted retries errorCode={}", errorCode);
            return;
        }
        workRepository.markRetry(
                work.workId(),
                claimToken,
                Instant.now().plus(retryDelay(work.attemptCount())),
                errorCode);
        metrics.record("apply", outcome);
        log.warn("Listing vector apply work deferred errorCode={}", errorCode);
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
