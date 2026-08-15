package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.PublicListingImageResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceipt;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceiptRepository;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSource;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingVectorCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class ListingVectorBackfillSnapshotReader {

    private final ListingDraftRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final ListingDiscoveryEmbeddingReceiptRepository receiptRepository;
    private final ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;

    public ListingVectorBackfillSnapshotReader(
            ListingDraftRepository listingRepository,
            ListingMediaRepository mediaRepository,
            ListingDiscoveryEmbeddingReceiptRepository receiptRepository,
            ListingDiscoveryEmbeddingSourceBuilder sourceBuilder) {
        this.listingRepository = listingRepository;
        this.mediaRepository = mediaRepository;
        this.receiptRepository = receiptRepository;
        this.sourceBuilder = sourceBuilder;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    // Captures one bounded authoritative snapshot; later promotion must close the post-snapshot mutation window.
    public Snapshot read(ListingVectorBackfillProperties properties) {
        return read(properties, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    // Applies the durable receipt watermark so post-watermark vectors remain missing rather than stale.
    public Snapshot read(ListingVectorBackfillProperties properties, Long maximumReceiptSequence) {
        List<ListingVectorBackfillDocument> documents = new ArrayList<>();
        String cursor = null;
        int rejectedReceipts = 0;
        while (true) {
            int remainingWithOverflowProbe = properties.maxDocuments() - documents.size() + 1;
            int pageSize = Math.min(properties.batchSize(), remainingWithOverflowProbe);
            List<ListingDraftRepository.ListingVectorBackfillRow> page =
                    listingRepository.findPublicListingsForVectorBackfillAfter(cursor, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (ListingDraftRepository.ListingVectorBackfillRow row : page) {
                if (documents.size() == properties.maxDocuments()) {
                    throw new ListingSearchUnavailableException(
                            "Listing vector backfill exceeds its document limit.");
                }
                DocumentResult result = document(row, maximumReceiptSequence);
                documents.add(result.document());
                rejectedReceipts += result.rejectedReceipt() ? 1 : 0;
                cursor = row.listing().id();
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        return new Snapshot(List.copyOf(documents), rejectedReceipts);
    }

    private DocumentResult document(
            ListingDraftRepository.ListingVectorBackfillRow row,
            Long maximumReceiptSequence) {
        ListingVectorExternalVersion.lexical(row.listingVersion());
        List<PublicListingImageResponse> images =
                mediaRepository.findPublicImagesByListingId(row.listing().id());
        ListingSearchDocument lexical = ListingSearchDocument.from(row.listing(), images);
        if (!"INDIVIDUAL".equals(row.listing().sellerType())) {
            return new DocumentResult(new ListingVectorBackfillDocument(
                    lexical,
                    row.listingVersion(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    "NOT_APPLICABLE",
                    null), false);
        }

        ListingDiscoveryEmbeddingSource source = sourceBuilder.build(
                row.listing(), row.listingVersion(), images);
        var receipt = maximumReceiptSequence == null
                ? receiptRepository.findExactCurrentForRebuild(
                        row.listing().id(),
                        row.listingVersion(),
                        source.documentHash(),
                        source.embeddingInputHash(),
                        source.normalizerVersion(),
                        source.redactorVersion(),
                        source.language(),
                        ListingDiscoveryEmbeddingSourceBuilder.PROVIDER,
                        ListingDiscoveryEmbeddingSourceBuilder.MODEL,
                        ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS)
                : receiptRepository.findExactCurrentForRebuild(
                        row.listing().id(),
                        row.listingVersion(),
                        source.documentHash(),
                        source.embeddingInputHash(),
                        source.normalizerVersion(),
                        source.redactorVersion(),
                        source.language(),
                        ListingDiscoveryEmbeddingSourceBuilder.PROVIDER,
                        ListingDiscoveryEmbeddingSourceBuilder.MODEL,
                        ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS,
                        maximumReceiptSequence);
        if (receipt.isEmpty()) {
            return new DocumentResult(document(lexical, row.listingVersion(), source, "MISSING", null), false);
        }
        try {
            ListingDiscoveryEmbeddingReceipt accepted = receipt.orElseThrow();
            requireExact(accepted, row.listingVersion(), source);
            ListingDiscoveryEmbeddingVectorCodec.DecodedVector decoded =
                    ListingDiscoveryEmbeddingVectorCodec.decode(accepted.vectorBytes());
            if (!constantTimeEquals(decoded.hash(), accepted.vectorHash())) {
                throw new IllegalArgumentException("Receipt vector hash is inconsistent.");
            }
            return new DocumentResult(
                    document(lexical, row.listingVersion(), source, "ATTACHED", decoded.values()),
                    false);
        } catch (IllegalArgumentException exception) {
            return new DocumentResult(
                    document(lexical, row.listingVersion(), source, "REJECTED", null),
                    true);
        }
    }

    private ListingVectorBackfillDocument document(
            ListingSearchDocument lexical,
            long listingVersion,
            ListingDiscoveryEmbeddingSource source,
            String state,
            float[] embedding) {
        return new ListingVectorBackfillDocument(
                lexical,
                listingVersion,
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                ListingDiscoveryEmbeddingSourceBuilder.PROVIDER,
                ListingDiscoveryEmbeddingSourceBuilder.MODEL,
                ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS,
                state,
                embedding);
    }

    private void requireExact(
            ListingDiscoveryEmbeddingReceipt receipt,
            long listingVersion,
            ListingDiscoveryEmbeddingSource source) {
        if (receipt.listingVersion() != listingVersion
                || !source.documentSchemaVersion().equals(receipt.documentSchemaVersion())
                || !source.documentHash().equals(receipt.documentHash())
                || !source.embeddingInputSchemaVersion().equals(receipt.embeddingInputSchemaVersion())
                || !source.embeddingInputHash().equals(receipt.embeddingInputHash())
                || !source.normalizerVersion().equals(receipt.normalizerVersion())
                || !source.redactorVersion().equals(receipt.redactorVersion())
                || !source.language().equals(receipt.language())
                || !ListingDiscoveryEmbeddingSourceBuilder.PROVIDER.equals(receipt.provider())
                || !ListingDiscoveryEmbeddingSourceBuilder.MODEL.equals(receipt.model())
                || ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS != receipt.dimensions()) {
            throw new IllegalArgumentException("Receipt identity is inconsistent.");
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.US_ASCII),
                        right.getBytes(StandardCharsets.US_ASCII));
    }

    public record Snapshot(
            List<ListingVectorBackfillDocument> documents,
            int rejectedReceiptCount
    ) {
    }

    private record DocumentResult(
            ListingVectorBackfillDocument document,
            boolean rejectedReceipt
    ) {
    }
}
