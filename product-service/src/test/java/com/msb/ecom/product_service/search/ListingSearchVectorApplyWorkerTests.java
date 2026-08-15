package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingSearchVectorApplyWorkerTests {

    private static final Instant NOW = Instant.parse("2026-07-23T14:00:00Z");
    private static final String WORK_ID = "01W00000000000000000000801";
    private static final String REQUEST_ID = "01R00000000000000000000801";
    private static final String LISTING_ID = "01L00000000000000000000801";
    private static final String V2 = "marketplace-listings-v2-g20260723140000000";
    private static final String CANDIDATE = "marketplace-listings-v2-g20260723140000001";

    @Mock ListingSearchVectorApplyWorkRepository workRepository;
    @Mock ListingSearchVectorDocumentResolver documentResolver;
    @Mock ListingSearchPromotionRepository promotionRepository;
    @Mock OpenSearchListingVectorBackfillClient vectorClient;
    @Mock ListingSearchVectorSyncMetrics metrics;
    @Mock UlidGenerator ulidGenerator;
    @Mock PlatformTransactionManager transactionManager;

    ListingSearchVectorApplyWorker worker;

    @BeforeEach
    void setUp() {
        lenient().when(vectorClient.vectorWritesAvailable()).thenReturn(true);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(ulidGenerator.next()).thenReturn("01C00000000000000000000801");
        worker = new ListingSearchVectorApplyWorker(
                properties(),
                workRepository,
                documentResolver,
                promotionRepository,
                new ListingSearchPromotionProperties(false, false, 5, 1000),
                vectorClient,
                metrics,
                ulidGenerator,
                transactionManager);
    }

    @Test
    void v1OnlyTargetDoesNotClaimWork() {
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.empty());
        when(vectorClient.resolveVectorTargets(Optional.empty()))
                .thenReturn(new OpenSearchListingVectorBackfillClient.VectorTargets(List.of()));

        worker.processPending();

        verify(workRepository, never()).claimBatch(anyString(), any(), any(), anyInt());
        verify(metrics).record("apply", "target_not_ready");
    }

    @Test
    void appliesCompleteDocumentToActiveV2AndMarksOnlyAfterSuccess() {
        ListingSearchVectorApplyWork work = work();
        ListingVectorBackfillDocument document = document();
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.empty());
        when(vectorClient.resolveVectorTargets(Optional.empty()))
                .thenReturn(new OpenSearchListingVectorBackfillClient.VectorTargets(List.of(V2)));
        when(workRepository.claimBatch(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(work));
        when(documentResolver.resolve(REQUEST_ID, LISTING_ID, 7)).thenReturn(document);
        when(workRepository.markApplied(
                eq(WORK_ID),
                eq("01C00000000000000000000801"),
                any()))
                .thenReturn(true);

        worker.processPending();

        verify(promotionRepository).acquireMutationFenceShared(5);
        verify(vectorClient).upsertVectorTarget(V2, document);
        verify(workRepository).markApplied(
                eq(WORK_ID),
                eq("01C00000000000000000000801"),
                any());
        verify(metrics).record("apply", "applied");
    }

    @Test
    void partialDualTargetFailureRemainsRetryable() {
        ListingSearchVectorApplyWork work = work();
        ListingVectorBackfillDocument document = document();
        ListingSearchRebuildRun run = new ListingSearchRebuildRun(
                "01B00000000000000000000801",
                CANDIDATE,
                "marketplace-listings-v1",
                "marketplace-listings-v1",
                0,
                0L,
                "CATCHING_UP",
                1,
                1,
                0,
                0,
                null,
                NOW,
                NOW,
                null);
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.of(run));
        when(vectorClient.resolveVectorTargets(Optional.of(CANDIDATE)))
                .thenReturn(new OpenSearchListingVectorBackfillClient.VectorTargets(
                        List.of(V2, CANDIDATE)));
        when(workRepository.claimBatch(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(work));
        when(documentResolver.resolve(REQUEST_ID, LISTING_ID, 7)).thenReturn(document);
        doAnswer(invocation -> {
            if (CANDIDATE.equals(invocation.getArgument(0, String.class))) {
                throw new ListingSearchUnavailableException("retry");
            }
            return null;
        }).when(vectorClient).upsertVectorTarget(anyString(), any());
        when(workRepository.markRetry(
                anyString(), anyString(), any(), anyString())).thenReturn(true);

        worker.processPending();

        verify(vectorClient).upsertVectorTarget(V2, document);
        verify(vectorClient).upsertVectorTarget(CANDIDATE, document);
        verify(workRepository, never()).markApplied(anyString(), anyString(), any());
        verify(workRepository).markRetry(
                eq(WORK_ID),
                eq("01C00000000000000000000801"),
                any(),
                eq("TARGET_UNAVAILABLE"));
    }

    @Test
    void staleReceiptCompletesWithoutOpenSearchWrite() {
        ListingSearchVectorApplyWork work = work();
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.empty());
        when(vectorClient.resolveVectorTargets(Optional.empty()))
                .thenReturn(new OpenSearchListingVectorBackfillClient.VectorTargets(List.of(V2)));
        when(workRepository.claimBatch(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(work));
        when(documentResolver.resolve(REQUEST_ID, LISTING_ID, 7))
                .thenThrow(new ListingSearchVectorStaleException());

        worker.processPending();

        verify(workRepository).markStale(
                eq(WORK_ID),
                eq("01C00000000000000000000801"),
                any(),
                eq("STALE"));
        verify(vectorClient, never()).upsertVectorTarget(anyString(), any());
    }

    @Test
    void malformedReceiptIsTerminalAndNeverLeaksVector() {
        ListingSearchVectorApplyWork work = work();
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.empty());
        when(vectorClient.resolveVectorTargets(Optional.empty()))
                .thenReturn(new OpenSearchListingVectorBackfillClient.VectorTargets(List.of(V2)));
        when(workRepository.claimBatch(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(work));
        when(documentResolver.resolve(REQUEST_ID, LISTING_ID, 7))
                .thenThrow(new ListingSearchVectorMalformedReceiptException());

        worker.processPending();

        verify(workRepository).markTerminal(
                eq(WORK_ID),
                eq("01C00000000000000000000801"),
                any(),
                eq("MALFORMED_RECEIPT"));
        verify(vectorClient, never()).upsertVectorTarget(anyString(), any());
    }

    @Test
    void exhaustedRetryBecomesTerminalWithoutFalseCompletion() {
        ListingSearchVectorApplyWork work = new ListingSearchVectorApplyWork(
                WORK_ID, REQUEST_ID, LISTING_ID, 7, 19, NOW);
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.empty());
        when(vectorClient.resolveVectorTargets(Optional.empty()))
                .thenReturn(new OpenSearchListingVectorBackfillClient.VectorTargets(List.of(V2)));
        when(workRepository.claimBatch(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(work));
        when(documentResolver.resolve(REQUEST_ID, LISTING_ID, 7))
                .thenThrow(new ListingSearchUnavailableException("retry"));

        worker.processPending();

        verify(workRepository).markTerminal(
                eq(WORK_ID),
                eq("01C00000000000000000000801"),
                any(),
                eq("TARGET_UNAVAILABLE"));
        verify(workRepository, never()).markApplied(anyString(), anyString(), any());
        verify(workRepository, never()).markRetry(
                anyString(), anyString(), any(), anyString());
        verify(metrics).record("apply", "exhausted");
    }

    @Test
    void interruptedWorkerStopsBeforeResolvingOrWritingClaimedWork() {
        when(promotionRepository.findDualWriteRun()).thenReturn(Optional.empty());
        when(vectorClient.resolveVectorTargets(Optional.empty()))
                .thenReturn(new OpenSearchListingVectorBackfillClient.VectorTargets(List.of(V2)));
        when(workRepository.claimBatch(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(work()));

        Thread.currentThread().interrupt();
        try {
            worker.processPending();
        } finally {
            Thread.interrupted();
        }

        verifyNoInteractions(documentResolver);
        verify(vectorClient, never()).upsertVectorTarget(anyString(), any());
        verify(metrics).record("apply", "cancelled");
    }

    @Test
    void disabledConfigurationCannotConstructWorker() {
        verifyNoInteractions(documentResolver);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ListingSearchVectorApplyWorker(
                        new ListingSearchVectorSyncProperties(
                                false, true, 25, 5000, 60, 20,
                                Duration.ofSeconds(2), Duration.ofMinutes(5), 100),
                        workRepository,
                        documentResolver,
                        promotionRepository,
                        new ListingSearchPromotionProperties(false, false, 5, 1000),
                        vectorClient,
                        metrics,
                        ulidGenerator,
                        transactionManager))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void workerRequiresReusableV2VectorClientAvailability() {
        when(vectorClient.vectorWritesAvailable()).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new ListingSearchVectorApplyWorker(
                        properties(),
                        workRepository,
                        documentResolver,
                        promotionRepository,
                        new ListingSearchPromotionProperties(false, false, 5, 1000),
                        vectorClient,
                        metrics,
                        ulidGenerator,
                        transactionManager))
                .isInstanceOf(IllegalStateException.class);
    }

    private ListingSearchVectorSyncProperties properties() {
        return new ListingSearchVectorSyncProperties(
                true,
                true,
                25,
                5000,
                60,
                20,
                Duration.ofSeconds(2),
                Duration.ofMinutes(5),
                100);
    }

    private ListingSearchVectorApplyWork work() {
        return new ListingSearchVectorApplyWork(
                WORK_ID, REQUEST_ID, LISTING_ID, 7, 0, NOW);
    }

    private ListingVectorBackfillDocument document() {
        float[] embedding = new float[1536];
        java.util.Arrays.fill(embedding, 0.25f);
        return new ListingVectorBackfillDocument(
                new ListingSearchDocument(
                        LISTING_ID,
                        "INDIVIDUAL",
                        "01C00000000000000000000801",
                        "furniture",
                        "Furniture",
                        "Desk",
                        "Public desk",
                        "Desk Public desk Furniture furniture GOOD Irvine Orange County",
                        "GOOD",
                        new BigDecimal("25.00"),
                        "USD",
                        "Irvine",
                        "irvine",
                        "Orange County",
                        "orange county",
                        NOW,
                        true,
                        null),
                7,
                "MARKETPLACE_LISTING_DISCOVERY_V2",
                "a".repeat(64),
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
                "b".repeat(64),
                "NFKC_WHITESPACE_V1",
                "PUBLIC_CONTACT_REDACTION_V1",
                "und",
                "openai",
                "text-embedding-3-small",
                1536,
                "ATTACHED",
                embedding);
    }
}
