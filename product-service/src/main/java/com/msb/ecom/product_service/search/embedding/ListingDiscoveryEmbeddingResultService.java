package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.core.id.Ulid;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.search.ListingSearchVectorApplyWorkRepository;
import com.msb.ecom.product_service.search.ListingSearchVectorSyncMetrics;
import com.msb.ecom.product_service.search.ListingSearchVectorSyncProperties;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class ListingDiscoveryEmbeddingResultService {

    private static final String ACK_SCHEMA_VERSION =
            "MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1";

    private final ListingDiscoveryEmbeddingProperties properties;
    private final ListingDiscoveryEmbeddingResultRequestParser parser;
    private final ListingDiscoveryEmbeddingRequestRepository requestRepository;
    private final ListingDiscoveryEmbeddingReceiptRepository receiptRepository;
    private final ListingDraftRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;
    private final ListingDiscoveryEmbeddingMetrics metrics;
    private final ListingSearchVectorSyncProperties vectorSyncProperties;
    private final ListingSearchVectorApplyWorkRepository vectorWorkRepository;
    private final ListingSearchVectorSyncMetrics vectorSyncMetrics;
    private final UlidGenerator ulidGenerator;
    private final String internalServiceToken;
    private final Clock clock;

    public ListingDiscoveryEmbeddingResultService(
            ListingDiscoveryEmbeddingProperties properties,
            ListingDiscoveryEmbeddingResultRequestParser parser,
            ListingDiscoveryEmbeddingRequestRepository requestRepository,
            ListingDiscoveryEmbeddingReceiptRepository receiptRepository,
            ListingDraftRepository listingRepository,
            ListingMediaRepository mediaRepository,
            ListingDiscoveryEmbeddingSourceBuilder sourceBuilder,
            ListingDiscoveryEmbeddingMetrics metrics,
            ListingSearchVectorSyncProperties vectorSyncProperties,
            ListingSearchVectorApplyWorkRepository vectorWorkRepository,
            ListingSearchVectorSyncMetrics vectorSyncMetrics,
            UlidGenerator ulidGenerator,
            @Value("${agent.internal-service-token}") String internalServiceToken,
            Clock clock) {
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new IllegalStateException("Agent internal service token must be configured.");
        }
        this.properties = properties;
        this.parser = parser;
        this.requestRepository = requestRepository;
        this.receiptRepository = receiptRepository;
        this.listingRepository = listingRepository;
        this.mediaRepository = mediaRepository;
        this.sourceBuilder = sourceBuilder;
        this.metrics = metrics;
        this.vectorSyncProperties = vectorSyncProperties;
        this.vectorWorkRepository = vectorWorkRepository;
        this.vectorSyncMetrics = vectorSyncMetrics;
        this.ulidGenerator = ulidGenerator;
        this.internalServiceToken = internalServiceToken;
        this.clock = clock;
    }

    @Transactional
    // Accepts only an exact current Product request and stores one canonical derived vector receipt.
    public ListingDiscoveryEmbeddingResultAcknowledgement accept(
            String suppliedToken,
            String requestId,
            byte[] body) {
        if (!properties.resultEnabled()) {
            metrics.record("result", "disabled");
            throw new ListingDiscoveryEmbeddingFeatureDisabledException();
        }
        requireInternalToken(suppliedToken);

        final String canonicalRequestId;
        final ListingDiscoveryEmbeddingResultRequest result;
        try {
            canonicalRequestId = Ulid.parse(requestId).value();
            result = parser.parse(body);
        } catch (NullPointerException | IllegalArgumentException exception) {
            metrics.record("result", "invalid");
            throw new IllegalArgumentException(
                    "The listing discovery embedding result is invalid.");
        }

        try {
            ListingDiscoveryEmbeddingRequest request = requestRepository
                    .findByRequestIdForUpdate(canonicalRequestId)
                    .orElseThrow(ListingDiscoveryEmbeddingStaleException::new);
            requireIdentity(request, result);
            requireResultMetadata(request, result);

            Optional<ListingDiscoveryEmbeddingReceipt> existing =
                    receiptRepository.findByRequestId(canonicalRequestId);
            if (existing.isPresent()) {
                if (exactReplay(existing.get(), request, result)) {
                    metrics.record("result", "replay");
                    return acknowledgement(canonicalRequestId);
                }
                throw new ListingDiscoveryEmbeddingIdempotencyConflictException();
            }

            requireCurrentSource(request);
            ListingDiscoveryEmbeddingReceipt receipt = receipt(request, result, clock.instant());
            if (!receiptRepository.insert(receipt)) {
                ListingDiscoveryEmbeddingReceipt concurrent = receiptRepository
                        .findByRequestId(canonicalRequestId)
                        .orElseThrow(ListingDiscoveryEmbeddingUnavailableException::new);
                if (exactReplay(concurrent, request, result)) {
                    metrics.record("result", "replay");
                    return acknowledgement(canonicalRequestId);
                }
                throw new ListingDiscoveryEmbeddingIdempotencyConflictException();
            }
            enqueueVectorWork(receipt);
            metrics.record("result", "accepted");
            return acknowledgement(canonicalRequestId);
        } catch (ListingDiscoveryEmbeddingStaleException exception) {
            metrics.record("result", "stale");
            throw exception;
        } catch (ListingDiscoveryEmbeddingIdentityConflictException exception) {
            metrics.record("result", "identity_mismatch");
            throw exception;
        } catch (ListingDiscoveryEmbeddingIdempotencyConflictException exception) {
            metrics.record("result", "conflict");
            throw exception;
        } catch (ListingDiscoveryEmbeddingUnavailableException exception) {
            metrics.record("result", "unavailable");
            throw exception;
        } catch (DataAccessException | IllegalStateException exception) {
            metrics.record("result", "unavailable");
            throw new ListingDiscoveryEmbeddingUnavailableException();
        }
    }

    // Couples the first accepted receipt to one durable vector intent in this transaction.
    private void enqueueVectorWork(ListingDiscoveryEmbeddingReceipt receipt) {
        if (!vectorSyncProperties.enabled()) {
            return;
        }
        boolean inserted = vectorWorkRepository.insertIfAbsent(
                ulidGenerator.next(),
                receipt.requestId(),
                receipt.listingId(),
                receipt.listingVersion(),
                receipt.acceptedAt());
        if (!inserted && !vectorWorkRepository.existsByRequestId(receipt.requestId())) {
            throw new ListingDiscoveryEmbeddingUnavailableException();
        }
        vectorSyncMetrics.record("receipt", inserted ? "enqueued" : "replay");
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            metrics.record("result", "unauthorized");
            throw new ListingAuthorizationException(
                    "Agent result authentication is required.");
        }
    }

    private void requireIdentity(
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingResultRequest result) {
        ListingDiscoveryEmbeddingResultRequest.EmbeddingIdentity identity =
                result.embeddingIdentity();
        if (!ListingDiscoveryEmbeddingSourceBuilder.PROVIDER.equals(request.provider())
                || !ListingDiscoveryEmbeddingSourceBuilder.MODEL.equals(request.model())
                || ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS != request.dimensions()
                || !request.provider().equals(identity.provider())
                || !request.model().equals(identity.model())
                || request.dimensions() != identity.dimensions()) {
            throw new ListingDiscoveryEmbeddingIdentityConflictException();
        }
    }

    private void requireResultMetadata(
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingResultRequest result) {
        if (!"REQUESTED".equals(request.state())
                || request.listingVersion() != result.listingVersion()
                || !request.documentSchemaVersion().equals(result.documentSchemaVersion())
                || !request.documentHash().equals(result.documentHash())
                || !request.embeddingInputSchemaVersion()
                        .equals(result.embeddingInputSchemaVersion())
                || !request.embeddingInputHash().equals(result.embeddingInputHash())) {
            throw new ListingDiscoveryEmbeddingStaleException();
        }
    }

    private void requireCurrentSource(ListingDiscoveryEmbeddingRequest request) {
        ListingDraftResponse current = listingRepository
                .findOptionalById(request.listingId())
                .filter(ListingDiscoveryEmbeddingRequestService::eligible)
                .filter(listing -> listing.version() == request.listingVersion())
                .orElseThrow(ListingDiscoveryEmbeddingStaleException::new);
        PublicListingResponse publicListing = listingRepository
                .findPublicListingById(current.id())
                .orElseThrow(ListingDiscoveryEmbeddingStaleException::new);
        final ListingDiscoveryEmbeddingSource source;
        try {
            source = sourceBuilder.build(
                    publicListing,
                    current.version(),
                    mediaRepository.findPublicImagesByListingId(current.id()));
        } catch (IllegalArgumentException exception) {
            throw new ListingDiscoveryEmbeddingStaleException();
        }
        if (!request.documentSchemaVersion().equals(source.documentSchemaVersion())
                || !request.documentHash().equals(source.documentHash())
                || !request.embeddingInputSchemaVersion()
                        .equals(source.embeddingInputSchemaVersion())
                || !request.embeddingInputHash().equals(source.embeddingInputHash())
                || !request.normalizerVersion().equals(source.normalizerVersion())
                || !request.redactorVersion().equals(source.redactorVersion())
                || !request.language().equals(source.language())) {
            throw new ListingDiscoveryEmbeddingStaleException();
        }
    }

    private boolean exactReplay(
            ListingDiscoveryEmbeddingReceipt receipt,
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingResultRequest result) {
        return receipt.requestId().equals(request.requestId())
                && receipt.listingId().equals(request.listingId())
                && receipt.listingVersion() == request.listingVersion()
                && receipt.documentSchemaVersion().equals(request.documentSchemaVersion())
                && receipt.documentHash().equals(request.documentHash())
                && receipt.embeddingInputSchemaVersion()
                        .equals(request.embeddingInputSchemaVersion())
                && receipt.embeddingInputHash().equals(request.embeddingInputHash())
                && receipt.normalizerVersion().equals(request.normalizerVersion())
                && receipt.redactorVersion().equals(request.redactorVersion())
                && receipt.language().equals(request.language())
                && receipt.provider().equals(request.provider())
                && receipt.model().equals(request.model())
                && receipt.dimensions() == request.dimensions()
                && receipt.vectorHash().equals(result.vector().hash())
                && MessageDigest.isEqual(receipt.vectorBytes(), result.vector().bytes());
    }

    private ListingDiscoveryEmbeddingReceipt receipt(
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingResultRequest result,
            Instant acceptedAt) {
        return new ListingDiscoveryEmbeddingReceipt(
                request.requestId(),
                request.listingId(),
                request.listingVersion(),
                request.documentSchemaVersion(),
                request.documentHash(),
                request.embeddingInputSchemaVersion(),
                request.embeddingInputHash(),
                request.normalizerVersion(),
                request.redactorVersion(),
                request.language(),
                request.provider(),
                request.model(),
                request.dimensions(),
                result.vector().hash(),
                result.vector().bytes(),
                acceptedAt);
    }

    private ListingDiscoveryEmbeddingResultAcknowledgement acknowledgement(
            String requestId) {
        return new ListingDiscoveryEmbeddingResultAcknowledgement(
                ACK_SCHEMA_VERSION,
                requestId,
                "ACCEPTED");
    }
}
