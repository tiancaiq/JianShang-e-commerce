package com.msb.ecom.product_service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceipt;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceiptRepository;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSource;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingVectorCodec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingVectorBackfillSnapshotReaderTests {

    private static final String LISTING_ID = "01L00000000000000000000511";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    private final ListingDraftRepository listings = mock(ListingDraftRepository.class);
    private final ListingMediaRepository media = mock(ListingMediaRepository.class);
    private final ListingDiscoveryEmbeddingReceiptRepository receipts =
            mock(ListingDiscoveryEmbeddingReceiptRepository.class);
    private final ListingDiscoveryEmbeddingSourceBuilder sourceBuilder =
            new ListingDiscoveryEmbeddingSourceBuilder(new ObjectMapper());
    private final ListingVectorBackfillSnapshotReader reader =
            new ListingVectorBackfillSnapshotReader(listings, media, receipts, sourceBuilder);

    @Test
    void exactCurrentReceiptAttachesAndMalformedReceiptIsLexicalOnly() {
        PublicListingResponse listing = listing("INDIVIDUAL");
        ListingDiscoveryEmbeddingSource source = sourceBuilder.build(listing, 7, List.of());
        var vector = vector(0.25d);
        ListingDiscoveryEmbeddingReceipt accepted = receipt(source, vector.bytes(), vector.hash());
        when(listings.findPublicListingsForVectorBackfillAfter(null, 100))
                .thenReturn(List.of(new ListingDraftRepository.ListingVectorBackfillRow(listing, 7)));
        when(listings.findPublicListingsForVectorBackfillAfter(LISTING_ID, 100))
                .thenReturn(List.of());
        when(media.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());
        when(receipts.findExactCurrentForRebuild(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(accepted));

        var exact = reader.read(properties());

        assertThat(exact.documents()).singleElement().satisfies(document -> {
            assertThat(document.embeddingState()).isEqualTo("ATTACHED");
            assertThat(document.embedding()).hasSize(1536).containsOnly(0.25f);
            assertThat(ListingVectorExternalVersion.vector(document.listingVersion())).isEqualTo(16);
        });
        assertThat(exact.rejectedReceiptCount()).isZero();

        when(receipts.findExactCurrentForRebuild(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Optional.of(receipt(source, new byte[4], vector.hash())));
        var rejected = reader.read(properties());
        assertThat(rejected.documents()).singleElement().satisfies(document -> {
            assertThat(document.embeddingState()).isEqualTo("REJECTED");
            assertThat(document.embedding()).isNull();
            assertThat(ListingVectorExternalVersion.lexical(document.listingVersion())).isEqualTo(15);
        });
        assertThat(rejected.rejectedReceiptCount()).isEqualTo(1);
    }

    @Test
    void businessListingRemainsLexicalOnlyAndNeverReadsEmbeddingReceipt() {
        PublicListingResponse listing = listing("BUSINESS");
        when(listings.findPublicListingsForVectorBackfillAfter(null, 100))
                .thenReturn(List.of(new ListingDraftRepository.ListingVectorBackfillRow(listing, 7)));
        when(listings.findPublicListingsForVectorBackfillAfter(LISTING_ID, 100))
                .thenReturn(List.of());
        when(media.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());

        var snapshot = reader.read(properties());

        assertThat(snapshot.documents()).singleElement().satisfies(document -> {
            assertThat(document.embeddingState()).isEqualTo("NOT_APPLICABLE");
            assertThat(document.embedding()).isNull();
            assertThat(document.documentHash()).isNull();
        });
        verify(receipts, never()).findExactCurrentForRebuild(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyInt());
    }

    private ListingDiscoveryEmbeddingVectorCodec.CanonicalVector vector(double value) {
        ArrayNode values = new ObjectMapper().createArrayNode();
        for (int index = 0; index < 1536; index++) {
            values.add(value);
        }
        return ListingDiscoveryEmbeddingVectorCodec.encode(values);
    }

    private ListingDiscoveryEmbeddingReceipt receipt(
            ListingDiscoveryEmbeddingSource source,
            byte[] bytes,
            String hash) {
        return new ListingDiscoveryEmbeddingReceipt(
                "01R00000000000000000000511",
                LISTING_ID,
                7,
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                "openai",
                "text-embedding-3-small",
                1536,
                hash,
                bytes,
                NOW);
    }

    private PublicListingResponse listing(String sellerType) {
        return new PublicListingResponse(
                LISTING_ID,
                sellerType,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                "01C00000000000000000000511",
                "furniture",
                "Furniture",
                "Desk",
                "Public desk",
                "GOOD",
                null,
                new BigDecimal("25.00"),
                "USD",
                false,
                1,
                "Irvine",
                "Orange County",
                NOW,
                null,
                0,
                0,
                List.of());
    }

    private ListingVectorBackfillProperties properties() {
        return new ListingVectorBackfillProperties(
                true,
                "marketplace-listings-v2",
                100,
                1000,
                5_000_000,
                true);
    }
}
