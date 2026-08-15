package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceipt;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceiptRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingSearchVectorCatchUpServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-23T15:00:00Z");
    private static final String REQUEST_ID = "01R00000000000000000000901";
    private static final String LISTING_ID = "01L00000000000000000000901";

    @Mock ListingDiscoveryEmbeddingReceiptRepository receiptRepository;
    @Mock ListingSearchVectorApplyWorkRepository workRepository;
    @Mock ListingSearchVectorDocumentResolver documentResolver;
    @Mock ListingSearchVectorSyncMetrics metrics;
    @Mock UlidGenerator ulidGenerator;

    @Test
    void disabledCatchUpPerformsZeroRepositoryWork() {
        var result = service(false).enqueueAfter(11);

        assertThat(result.nextReceiptSequence()).isEqualTo(11);
        verifyNoInteractions(receiptRepository, workRepository, documentResolver);
        verify(metrics).record("catch_up", "disabled");
    }

    @Test
    void exactCurrentReceiptIsEnqueuedOnceWithStableCursor() {
        ListingDiscoveryEmbeddingReceipt receipt = receipt();
        when(receiptRepository.findWithoutVectorWorkAfter(10, 100))
                .thenReturn(List.of(
                        new ListingDiscoveryEmbeddingReceiptRepository.ReceiptCursor(
                                12, receipt)));
        when(ulidGenerator.next()).thenReturn("01W00000000000000000000901");
        when(workRepository.insertIfAbsent(
                "01W00000000000000000000901",
                REQUEST_ID,
                LISTING_ID,
                7,
                NOW)).thenReturn(true);

        var result = service(true).enqueueAfter(10);

        assertThat(result).isEqualTo(
                new ListingSearchVectorCatchUpService.CatchUpResult(12, 1, 0, 0));
        verify(documentResolver).resolve(REQUEST_ID, LISTING_ID, 7);
    }

    @Test
    void staleAndMalformedHistoryAdvanceCursorWithoutEnqueue() {
        ListingDiscoveryEmbeddingReceipt stale = receipt();
        ListingDiscoveryEmbeddingReceipt malformed = new ListingDiscoveryEmbeddingReceipt(
                "01R00000000000000000000902",
                "01L00000000000000000000902",
                7,
                stale.documentSchemaVersion(),
                stale.documentHash(),
                stale.embeddingInputSchemaVersion(),
                stale.embeddingInputHash(),
                stale.normalizerVersion(),
                stale.redactorVersion(),
                stale.language(),
                stale.provider(),
                stale.model(),
                stale.dimensions(),
                stale.vectorHash(),
                stale.vectorBytes(),
                NOW);
        when(receiptRepository.findWithoutVectorWorkAfter(0, 100))
                .thenReturn(List.of(
                        new ListingDiscoveryEmbeddingReceiptRepository.ReceiptCursor(1, stale),
                        new ListingDiscoveryEmbeddingReceiptRepository.ReceiptCursor(2, malformed)));
        when(documentResolver.resolve(REQUEST_ID, LISTING_ID, 7))
                .thenThrow(new ListingSearchVectorStaleException());
        when(documentResolver.resolve(
                malformed.requestId(), malformed.listingId(), 7))
                .thenThrow(new ListingSearchVectorMalformedReceiptException());

        var result = service(true).enqueueAfter(0);

        assertThat(result).isEqualTo(
                new ListingSearchVectorCatchUpService.CatchUpResult(2, 0, 1, 1));
        verify(workRepository, never()).insertIfAbsent(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    private ListingSearchVectorCatchUpService service(boolean enabled) {
        return new ListingSearchVectorCatchUpService(
                new ListingSearchVectorSyncProperties(
                        enabled,
                        false,
                        25,
                        5000,
                        60,
                        20,
                        Duration.ofSeconds(2),
                        Duration.ofMinutes(5),
                        100),
                receiptRepository,
                workRepository,
                documentResolver,
                metrics,
                ulidGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ListingDiscoveryEmbeddingReceipt receipt() {
        return new ListingDiscoveryEmbeddingReceipt(
                REQUEST_ID,
                LISTING_ID,
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
                "c".repeat(64),
                new byte[6144],
                NOW);
    }
}
