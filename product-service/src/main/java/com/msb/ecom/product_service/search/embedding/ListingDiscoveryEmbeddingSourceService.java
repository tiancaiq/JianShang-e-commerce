package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.core.id.Ulid;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class ListingDiscoveryEmbeddingSourceService {

    private static final String RESPONSE_SCHEMA_VERSION =
            "MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1";

    private final ListingDiscoveryEmbeddingProperties properties;
    private final ListingDiscoveryEmbeddingRequestRepository requestRepository;
    private final ListingDraftRepository listingRepository;
    private final ListingMediaRepository mediaRepository;
    private final ListingDiscoveryEmbeddingSourceBuilder sourceBuilder;
    private final ListingDiscoveryEmbeddingMetrics metrics;
    private final String internalServiceToken;

    public ListingDiscoveryEmbeddingSourceService(
            ListingDiscoveryEmbeddingProperties properties,
            ListingDiscoveryEmbeddingRequestRepository requestRepository,
            ListingDraftRepository listingRepository,
            ListingMediaRepository mediaRepository,
            ListingDiscoveryEmbeddingSourceBuilder sourceBuilder,
            ListingDiscoveryEmbeddingMetrics metrics,
            @Value("${agent.internal-service-token}") String internalServiceToken) {
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new IllegalStateException("Agent internal service token must be configured.");
        }
        this.properties = properties;
        this.requestRepository = requestRepository;
        this.listingRepository = listingRepository;
        this.mediaRepository = mediaRepository;
        this.sourceBuilder = sourceBuilder;
        this.metrics = metrics;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    // Resolves a request-bound source only while the exact Product listing version remains public and current.
    public ListingDiscoveryEmbeddingSourceResponse readExact(
            String suppliedToken,
            String requestId) {
        if (!properties.sourceEnabled()) {
            metrics.record("source", "disabled");
            throw new ListingDiscoveryEmbeddingFeatureDisabledException();
        }
        requireInternalToken(suppliedToken);
        String normalizedRequestId = requestId(requestId);
        try {
            ListingDiscoveryEmbeddingRequest request = requestRepository
                    .findByRequestId(normalizedRequestId)
                    .orElseThrow(ListingDiscoveryEmbeddingSourceNotFoundException::new);
            ListingDraftResponse current = listingRepository.findOptionalById(request.listingId())
                    .filter(ListingDiscoveryEmbeddingRequestService::eligible)
                    .filter(listing -> listing.version() == request.listingVersion())
                    .orElseThrow(ListingDiscoveryEmbeddingSourceNotFoundException::new);
            PublicListingResponse publicListing = listingRepository.findPublicListingById(current.id())
                    .orElseThrow(ListingDiscoveryEmbeddingSourceNotFoundException::new);
            ListingDiscoveryEmbeddingSource source = sourceBuilder.build(
                    publicListing,
                    current.version(),
                    mediaRepository.findPublicImagesByListingId(current.id()));
            if (!matches(request, source)) {
                throw new ListingDiscoveryEmbeddingSourceNotFoundException();
            }
            metrics.record("source", "success");
            return response(request, source);
        } catch (ListingDiscoveryEmbeddingSourceNotFoundException exception) {
            metrics.record("source", "not_found");
            throw exception;
        } catch (DataAccessException exception) {
            metrics.record("source", "unavailable");
            throw new ListingDiscoveryEmbeddingUnavailableException();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            metrics.record("source", "unavailable");
            throw new ListingDiscoveryEmbeddingUnavailableException();
        }
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            metrics.record("source", "unauthorized");
            throw new ListingAuthorizationException("Agent source authentication is required.");
        }
    }

    private String requestId(String value) {
        try {
            return Ulid.parse(value).value();
        } catch (NullPointerException | IllegalArgumentException exception) {
            metrics.record("source", "invalid");
            throw new IllegalArgumentException("Embedding request ID is invalid.");
        }
    }

    private boolean matches(
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingSource source) {
        return "REQUESTED".equals(request.state())
                && request.documentSchemaVersion().equals(source.documentSchemaVersion())
                && request.documentHash().equals(source.documentHash())
                && request.embeddingInputSchemaVersion().equals(source.embeddingInputSchemaVersion())
                && request.embeddingInputHash().equals(source.embeddingInputHash())
                && request.normalizerVersion().equals(source.normalizerVersion())
                && request.redactorVersion().equals(source.redactorVersion())
                && request.language().equals(source.language())
                && ListingDiscoveryEmbeddingSourceBuilder.PROVIDER.equals(request.provider())
                && ListingDiscoveryEmbeddingSourceBuilder.MODEL.equals(request.model())
                && ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS == request.dimensions();
    }

    private ListingDiscoveryEmbeddingSourceResponse response(
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingSource source) {
        return new ListingDiscoveryEmbeddingSourceResponse(
                RESPONSE_SCHEMA_VERSION,
                request.requestId(),
                request.listingId(),
                request.listingVersion(),
                source.documentSchemaVersion(),
                source.documentHash(),
                source.embeddingInputSchemaVersion(),
                source.embeddingInputHash(),
                source.normalizerVersion(),
                source.redactorVersion(),
                source.language(),
                new ListingDiscoveryEmbeddingSourceResponse.EmbeddingIdentity(
                        request.provider(),
                        request.model(),
                        request.dimensions()),
                source.embeddingText());
    }
}
