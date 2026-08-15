package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingDiscoveryEmbeddingSourceServiceTests {

    private static final String TOKEN = "test-agent-token";
    private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAD";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @Mock ListingDiscoveryEmbeddingRequestRepository requestRepository;
    @Mock ListingDraftRepository listingRepository;
    @Mock ListingMediaRepository mediaRepository;
    @Mock ListingDiscoveryEmbeddingMetrics metrics;

    ListingDiscoveryEmbeddingSourceBuilder builder =
            new ListingDiscoveryEmbeddingSourceBuilder(new ObjectMapper());

    @Test
    void disabledGatePrecedesAuthenticationValidationAndRepositoryLookup() {
        assertThatThrownBy(() -> service(false).readExact("wrong", "malformed"))
                .isInstanceOf(ListingDiscoveryEmbeddingFeatureDisabledException.class);

        verifyNoInteractions(requestRepository, listingRepository, mediaRepository);
    }

    @Test
    void serviceAuthenticationPrecedesRequestValidationAndRepositoryLookup() {
        assertThatThrownBy(() -> service(true).readExact("wrong", "malformed"))
                .isInstanceOf(ListingAuthorizationException.class);

        verifyNoInteractions(requestRepository, listingRepository, mediaRepository);
    }

    @Test
    void malformedCanonicalRequestIdIsRejectedBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service(true).readExact(TOKEN, "01D0000000000000000000010I"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(requestRepository, listingRepository, mediaRepository);
    }

    @Test
    void exactRequestReturnsOnlyCurrentMatchingPublicSource() {
        ListingDiscoveryEmbeddingSource expected = builder.build(publicListing(), 7, List.of());
        when(requestRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.of(request(expected)));
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing("ACTIVE", "APPROVED", 7)));
        when(listingRepository.findPublicListingById(LISTING_ID))
                .thenReturn(Optional.of(publicListing()));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());

        ListingDiscoveryEmbeddingSourceResponse response = service(true).readExact(TOKEN, REQUEST_ID);

        assertThat(response.schemaVersion())
                .isEqualTo("MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1");
        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.listingId()).isEqualTo(LISTING_ID);
        assertThat(response.listingVersion()).isEqualTo(7);
        assertThat(response.embeddingIdentity())
                .extracting(
                        ListingDiscoveryEmbeddingSourceResponse.EmbeddingIdentity::provider,
                        ListingDiscoveryEmbeddingSourceResponse.EmbeddingIdentity::model,
                        ListingDiscoveryEmbeddingSourceResponse.EmbeddingIdentity::dimensions)
                .containsExactly("openai", "text-embedding-3-small", 1536);
        assertThat(response.embeddingText())
                .contains("TITLE", "CATEGORY", "DESCRIPTION")
                .doesNotContain("Irvine", "USD", "seller");
    }

    @Test
    void missingStaleIneligibleDeletedAndChangedHashesAreNonEnumerating() {
        ListingDiscoveryEmbeddingSource expected = builder.build(publicListing(), 7, List.of());
        ListingDiscoveryEmbeddingRequest request = request(expected);
        when(requestRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(request))
                .thenReturn(Optional.of(request))
                .thenReturn(Optional.of(request))
                .thenReturn(Optional.of(request));
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing("ACTIVE", "APPROVED", 8)))
                .thenReturn(Optional.of(listing("CLOSED", "APPROVED", 7)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(listing("ACTIVE", "APPROVED", 7)));
        when(listingRepository.findPublicListingById(LISTING_ID))
                .thenReturn(Optional.of(publicListingWithTitle("Changed title")));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service(true).readExact(TOKEN, REQUEST_ID))
                    .isInstanceOf(ListingDiscoveryEmbeddingSourceNotFoundException.class);
        }
    }

    @Test
    void databaseFailureMapsToSafeUnavailableWithoutLeakingDetails() {
        when(requestRepository.findByRequestId(REQUEST_ID))
                .thenThrow(new DataAccessResourceFailureException("jdbc secret detail"));

        assertThatThrownBy(() -> service(true).readExact(TOKEN, REQUEST_ID))
                .isInstanceOf(ListingDiscoveryEmbeddingUnavailableException.class)
                .hasMessage("Listing discovery embedding source is temporarily unavailable.");
    }

    private ListingDiscoveryEmbeddingSourceService service(boolean enabled) {
        return new ListingDiscoveryEmbeddingSourceService(
                new ListingDiscoveryEmbeddingProperties(
                        false,
                        enabled,
                        false,
                        "listing-discovery-embedding-request-v1"),
                requestRepository,
                listingRepository,
                mediaRepository,
                builder,
                metrics,
                TOKEN);
    }

    private ListingDiscoveryEmbeddingRequest request(ListingDiscoveryEmbeddingSource source) {
        return new ListingDiscoveryEmbeddingRequest(
                REQUEST_ID,
                "01ARZ3NDEKTSV4RRFFQ69G5FAE",
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
                "REQUESTED",
                NOW);
    }

    private ListingDraftResponse listing(String status, String moderationStatus, long version) {
        return new ListingDraftResponse(
                LISTING_ID,
                "INDIVIDUAL",
                "01ARZ3NDEKTSV4RRFFQ69G5FAA",
                null,
                null,
                null,
                "01ARZ3NDEKTSV4RRFFQ69G5FAB",
                "Desk",
                "Public description",
                "GOOD",
                null,
                BigDecimal.TEN,
                "USD",
                false,
                null,
                1,
                "Irvine",
                "Orange County",
                status,
                moderationStatus,
                "ADMIN_REVIEW",
                NOW,
                version,
                NOW,
                NOW,
                null,
                null,
                null,
                List.of());
    }

    private PublicListingResponse publicListing() {
        return publicListingWithTitle("Desk");
    }

    private PublicListingResponse publicListingWithTitle(String title) {
        return new PublicListingResponse(
                LISTING_ID,
                "INDIVIDUAL",
                "01ARZ3NDEKTSV4RRFFQ69G5FAA",
                "Seller",
                null,
                null,
                null,
                null,
                false,
                "01ARZ3NDEKTSV4RRFFQ69G5FAB",
                "furniture",
                "Furniture",
                title,
                "Public description",
                "GOOD",
                null,
                BigDecimal.TEN,
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
}
