package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceipt;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingReceiptRepository;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingRequest;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingRequestRepository;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSource;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingVectorCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class ListingSearchVectorDocumentResolver {

    private final ListingDiscoveryEmbeddingRequestRepository requestRepository;
    private final ListingDiscoveryEmbeddingReceiptRepository receiptRepository;
    private final ListingDraftRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;
    private final ListingVectorProjectionDocumentFactory documentFactory;

    // Revalidates every authoritative identity before exposing a transient vector document.
    public ListingVectorBackfillDocument resolve(
            String requestId,
            String listingId,
            long listingVersion) {
        ListingDiscoveryEmbeddingRequest request = requestRepository
                .findByRequestId(requestId)
                .orElseThrow(ListingSearchVectorStaleException::new);
        ListingDiscoveryEmbeddingReceipt receipt = receiptRepository
                .findByRequestId(requestId)
                .orElseThrow(ListingSearchVectorStaleException::new);
        if (!requestId.equals(request.requestId())
                || !requestId.equals(receipt.requestId())
                || !listingId.equals(request.listingId())
                || !listingId.equals(receipt.listingId())
                || listingVersion != request.listingVersion()
                || listingVersion != receipt.listingVersion()
                || !"REQUESTED".equals(request.state())) {
            throw new ListingSearchVectorStaleException();
        }

        ListingDraftResponse current = listingRepository
                .findOptionalById(listingId)
                .filter(ListingSearchProjectionIntentService::isPubliclySearchable)
                .filter(listing -> "INDIVIDUAL".equals(listing.sellerType()))
                .filter(listing -> listing.version() == listingVersion)
                .orElseThrow(ListingSearchVectorStaleException::new);
        PublicListingResponse publicListing = listingRepository
                .findPublicListingById(listingId)
                .orElseThrow(ListingSearchVectorStaleException::new);
        var images = mediaRepository.findPublicImagesByListingId(listingId);
        final ListingDiscoveryEmbeddingSource source;
        try {
            source = sourceBuilder.build(publicListing, listingVersion, images);
        } catch (IllegalArgumentException exception) {
            throw new ListingSearchVectorStaleException();
        }
        requireExact(request, receipt, source);

        final ListingDiscoveryEmbeddingVectorCodec.DecodedVector decoded;
        try {
            decoded = ListingDiscoveryEmbeddingVectorCodec.decode(receipt.vectorBytes());
        } catch (IllegalArgumentException exception) {
            throw new ListingSearchVectorMalformedReceiptException();
        }
        if (!constantTimeEquals(decoded.hash(), receipt.vectorHash())) {
            throw new ListingSearchVectorMalformedReceiptException();
        }
        return documentFactory.vector(
                current,
                publicListing,
                images,
                source,
                decoded.values());
    }

    private void requireExact(
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingReceipt receipt,
            ListingDiscoveryEmbeddingSource source) {
        if (!request.documentSchemaVersion().equals(source.documentSchemaVersion())
                || !request.documentHash().equals(source.documentHash())
                || !request.embeddingInputSchemaVersion()
                        .equals(source.embeddingInputSchemaVersion())
                || !request.embeddingInputHash().equals(source.embeddingInputHash())
                || !request.normalizerVersion().equals(source.normalizerVersion())
                || !request.redactorVersion().equals(source.redactorVersion())
                || !request.language().equals(source.language())
                || !request.documentSchemaVersion().equals(receipt.documentSchemaVersion())
                || !request.documentHash().equals(receipt.documentHash())
                || !request.embeddingInputSchemaVersion()
                        .equals(receipt.embeddingInputSchemaVersion())
                || !request.embeddingInputHash().equals(receipt.embeddingInputHash())
                || !request.normalizerVersion().equals(receipt.normalizerVersion())
                || !request.redactorVersion().equals(receipt.redactorVersion())
                || !request.language().equals(receipt.language())
                || !ListingDiscoveryEmbeddingSourceBuilder.PROVIDER.equals(request.provider())
                || !ListingDiscoveryEmbeddingSourceBuilder.MODEL.equals(request.model())
                || ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS != request.dimensions()
                || !request.provider().equals(receipt.provider())
                || !request.model().equals(receipt.model())
                || request.dimensions() != receipt.dimensions()) {
            throw new ListingSearchVectorStaleException();
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.US_ASCII),
                        right.getBytes(StandardCharsets.US_ASCII));
    }
}
