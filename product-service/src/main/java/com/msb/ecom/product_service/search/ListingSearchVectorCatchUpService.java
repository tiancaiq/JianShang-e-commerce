package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceiptRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class ListingSearchVectorCatchUpService {

    private final ListingSearchVectorSyncProperties properties;
    private final ListingDiscoveryEmbeddingReceiptRepository receiptRepository;
    private final ListingSearchVectorApplyWorkRepository workRepository;
    private final ListingSearchVectorDocumentResolver documentResolver;
    private final ListingSearchVectorSyncMetrics metrics;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;

    @Transactional
    // Enqueues only exact-current receipts while returning a stable cursor over skipped history.
    public CatchUpResult enqueueAfter(long afterReceiptSequence) {
        if (!properties.enabled()) {
            metrics.record("catch_up", "disabled");
            return new CatchUpResult(afterReceiptSequence, 0, 0, 0);
        }
        var page = receiptRepository.findWithoutVectorWorkAfter(
                Math.max(0, afterReceiptSequence),
                properties.effectiveCatchUpBatchSize());
        long next = afterReceiptSequence;
        int enqueued = 0;
        int stale = 0;
        int malformed = 0;
        for (var item : page) {
            next = item.receiptSequence();
            var receipt = item.receipt();
            try {
                documentResolver.resolve(
                        receipt.requestId(),
                        receipt.listingId(),
                        receipt.listingVersion());
                if (workRepository.insertIfAbsent(
                        ulidGenerator.next(),
                        receipt.requestId(),
                        receipt.listingId(),
                        receipt.listingVersion(),
                        clock.instant())) {
                    enqueued++;
                }
            } catch (ListingSearchVectorStaleException exception) {
                stale++;
            } catch (ListingSearchVectorMalformedReceiptException exception) {
                malformed++;
            }
        }
        metrics.record("catch_up", enqueued > 0 ? "enqueued" : "no_work");
        return new CatchUpResult(next, enqueued, stale, malformed);
    }

    public record CatchUpResult(
            long nextReceiptSequence,
            int enqueuedCount,
            int staleCount,
            int malformedCount
    ) {
    }
}
