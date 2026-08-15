package com.msb.ecom.product_service.search.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.search.ListingSearchVectorApplyWorkRepository;
import com.msb.ecom.product_service.search.ListingSearchVectorSyncMetrics;
import com.msb.ecom.product_service.search.ListingSearchVectorSyncProperties;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingDiscoveryEmbeddingResultServiceTests {

    private static final String TOKEN = "test-agent-token";
    private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAD";
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @Mock ListingDiscoveryEmbeddingResultRequestParser parser;
    @Mock ListingDiscoveryEmbeddingRequestRepository requestRepository;
    @Mock ListingDiscoveryEmbeddingReceiptRepository receiptRepository;
    @Mock ListingDraftRepository listingRepository;
    @Mock ListingMediaRepository mediaRepository;
    @Mock ListingDiscoveryEmbeddingMetrics metrics;
    @Mock ListingSearchVectorApplyWorkRepository vectorWorkRepository;
    @Mock ListingSearchVectorSyncMetrics vectorSyncMetrics;
    @Mock UlidGenerator ulidGenerator;

    ListingDiscoveryEmbeddingSourceBuilder builder =
            new ListingDiscoveryEmbeddingSourceBuilder(new ObjectMapper());

    @Test
    void disabledAndAuthenticationGatesPrecedeParsingAndRepositories() {
        assertThatThrownBy(() -> service(false).accept("wrong", "bad", new byte[0]))
                .isInstanceOf(ListingDiscoveryEmbeddingFeatureDisabledException.class);
        assertThatThrownBy(() -> service(true).accept("wrong", "bad", new byte[0]))
                .isInstanceOf(ListingAuthorizationException.class);

        verifyNoInteractions(
                parser,
                requestRepository,
                receiptRepository,
                listingRepository,
                mediaRepository,
                vectorWorkRepository);
    }

    @Test
    void exactCurrentResultStoresOnePrivateCanonicalReceiptAndReturnsStrictAck() {
        ListingDiscoveryEmbeddingSource source = source();
        ListingDiscoveryEmbeddingResultRequest result = result(vector(0.25d));
        when(parser.parse(any())).thenReturn(result);
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(request(source)));
        when(receiptRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        currentListing(source);
        when(receiptRepository.insert(any())).thenReturn(true);

        ListingDiscoveryEmbeddingResultAcknowledgement response =
                service(true).accept(TOKEN, REQUEST_ID, new byte[]{1});

        assertThat(response).isEqualTo(new ListingDiscoveryEmbeddingResultAcknowledgement(
                "MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1",
                REQUEST_ID,
                "ACCEPTED"));
        ArgumentCaptor<ListingDiscoveryEmbeddingReceipt> receipt =
                ArgumentCaptor.forClass(ListingDiscoveryEmbeddingReceipt.class);
        verify(receiptRepository).insert(receipt.capture());
        assertThat(receipt.getValue().vectorBytes()).hasSize(6144);
        assertThat(receipt.getValue().vectorHash()).matches("[0-9a-f]{64}");
        assertThat(receipt.getValue().acceptedAt()).isEqualTo(NOW);
        verifyNoInteractions(vectorWorkRepository);
    }

    @Test
    void firstAcceptanceAtomicallyEnqueuesOneVectorIntentWhenEnabled() {
        ListingDiscoveryEmbeddingSource source = source();
        when(parser.parse(any())).thenReturn(result(vector(0.25d)));
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(request(source)));
        when(receiptRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        currentListing(source);
        when(receiptRepository.insert(any())).thenReturn(true);
        when(ulidGenerator.next()).thenReturn("01ARZ3NDEKTSV4RRFFQ69G5FAH");
        when(vectorWorkRepository.insertIfAbsent(
                any(), any(), any(), anyLong(), any()))
                .thenReturn(true);

        service(true, true).accept(TOKEN, REQUEST_ID, new byte[]{1});

        verify(vectorWorkRepository).insertIfAbsent(
                "01ARZ3NDEKTSV4RRFFQ69G5FAH",
                REQUEST_ID,
                LISTING_ID,
                7,
                NOW);
        verify(vectorSyncMetrics).record("receipt", "enqueued");
    }

    @Test
    void exactReplayReturnsAckWithoutListingOrSourceWork() {
        ListingDiscoveryEmbeddingSource source = source();
        ListingDiscoveryEmbeddingResultRequest result = result(vector(0.25d));
        ListingDiscoveryEmbeddingRequest request = request(source);
        when(parser.parse(any())).thenReturn(result);
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(request));
        when(receiptRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.of(receipt(request, result)));

        ListingDiscoveryEmbeddingResultAcknowledgement response =
                service(true).accept(TOKEN, REQUEST_ID, new byte[]{1});

        assertThat(response.outcome()).isEqualTo("ACCEPTED");
        verifyNoInteractions(listingRepository, mediaRepository);
        verify(receiptRepository, never()).insert(any());
        verify(metrics).record("result", "replay");
    }

    @Test
    void changedReplayVectorIsAnIdempotencyConflict() {
        ListingDiscoveryEmbeddingSource source = source();
        ListingDiscoveryEmbeddingRequest request = request(source);
        ListingDiscoveryEmbeddingResultRequest accepted = result(vector(0.25d));
        ListingDiscoveryEmbeddingResultRequest changed = result(vector(0.5d));
        when(parser.parse(any())).thenReturn(changed);
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(request));
        when(receiptRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.of(receipt(request, accepted)));

        assertThatThrownBy(() -> service(true).accept(TOKEN, REQUEST_ID, new byte[]{1}))
                .isInstanceOf(ListingDiscoveryEmbeddingIdempotencyConflictException.class);

        verifyNoInteractions(listingRepository, mediaRepository);
    }

    @Test
    void identityMismatchIsDistinctAndStopsBeforeListingWork() {
        ListingDiscoveryEmbeddingSource source = source();
        ListingDiscoveryEmbeddingResultRequest current = result(vector(0.25d));
        ListingDiscoveryEmbeddingResultRequest mismatch =
                new ListingDiscoveryEmbeddingResultRequest(
                        current.schemaVersion(),
                        current.listingVersion(),
                        current.documentSchemaVersion(),
                        current.documentHash(),
                        current.embeddingInputSchemaVersion(),
                        current.embeddingInputHash(),
                        new ListingDiscoveryEmbeddingResultRequest.EmbeddingIdentity(
                                "other",
                                current.embeddingIdentity().model(),
                                current.embeddingIdentity().dimensions()),
                        current.vector());
        when(parser.parse(any())).thenReturn(mismatch);
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(request(source)));

        assertThatThrownBy(() -> service(true).accept(TOKEN, REQUEST_ID, new byte[]{1}))
                .isInstanceOf(ListingDiscoveryEmbeddingIdentityConflictException.class);

        verifyNoInteractions(receiptRepository, listingRepository, mediaRepository);
    }

    @Test
    void missingCrossRequestVersionHashDeletedAndIneligibleAreStale() {
        ListingDiscoveryEmbeddingSource source = source();
        ListingDiscoveryEmbeddingRequest request = request(source);
        ListingDiscoveryEmbeddingResultRequest result = result(vector(0.25d));
        ListingDiscoveryEmbeddingResultRequest changedHash =
                new ListingDiscoveryEmbeddingResultRequest(
                        result.schemaVersion(),
                        result.listingVersion(),
                        result.documentSchemaVersion(),
                        "c".repeat(64),
                        result.embeddingInputSchemaVersion(),
                        result.embeddingInputHash(),
                        result.embeddingIdentity(),
                        result.vector());
        when(parser.parse(any()))
                .thenReturn(result)
                .thenReturn(changedHash)
                .thenReturn(result)
                .thenReturn(result)
                .thenReturn(result);
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(request))
                .thenReturn(Optional.of(request))
                .thenReturn(Optional.of(request))
                .thenReturn(Optional.of(request));
        when(receiptRepository.findByRequestId(REQUEST_ID))
                .thenReturn(Optional.empty());
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing("ACTIVE", "APPROVED", 8)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(listing("CLOSED", "APPROVED", 7)));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service(true).accept(TOKEN, REQUEST_ID, new byte[]{1}))
                    .isInstanceOf(ListingDiscoveryEmbeddingStaleException.class);
        }
        verify(receiptRepository, never()).insert(any());
    }

    @Test
    void changedCanonicalSourceCannotResurrectListing() {
        ListingDiscoveryEmbeddingSource source = source();
        ListingDiscoveryEmbeddingResultRequest result = result(vector(0.25d));
        when(parser.parse(any())).thenReturn(result);
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(request(source)));
        when(receiptRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing("ACTIVE", "APPROVED", 7)));
        when(listingRepository.findPublicListingById(LISTING_ID))
                .thenReturn(Optional.of(publicListing("Changed title")));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service(true).accept(TOKEN, REQUEST_ID, new byte[]{1}))
                .isInstanceOf(ListingDiscoveryEmbeddingStaleException.class);

        verify(receiptRepository, never()).insert(any());
    }

    @Test
    void persistenceFailureMapsToSafeUnavailableAndNeverAcknowledges() {
        ListingDiscoveryEmbeddingSource source = source();
        ListingDiscoveryEmbeddingResultRequest result = result(vector(0.25d));
        when(parser.parse(any())).thenReturn(result);
        when(requestRepository.findByRequestIdForUpdate(REQUEST_ID))
                .thenReturn(Optional.of(request(source)));
        when(receiptRepository.findByRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        currentListing(source);
        when(receiptRepository.insert(any()))
                .thenThrow(new DataAccessResourceFailureException("jdbc secret"));

        assertThatThrownBy(() -> service(true).accept(TOKEN, REQUEST_ID, new byte[]{1}))
                .isInstanceOf(ListingDiscoveryEmbeddingUnavailableException.class)
                .hasMessage("Listing discovery embedding source is temporarily unavailable.");
    }

    private ListingDiscoveryEmbeddingResultService service(boolean enabled) {
        return service(enabled, false);
    }

    private ListingDiscoveryEmbeddingResultService service(
            boolean enabled,
            boolean vectorSyncEnabled) {
        return new ListingDiscoveryEmbeddingResultService(
                new ListingDiscoveryEmbeddingProperties(
                        false,
                        false,
                        enabled,
                        "listing-discovery-embedding-request-v1"),
                parser,
                requestRepository,
                receiptRepository,
                listingRepository,
                mediaRepository,
                builder,
                metrics,
                new ListingSearchVectorSyncProperties(
                        vectorSyncEnabled,
                        false,
                        25,
                        5000,
                        60,
                        20,
                        java.time.Duration.ofSeconds(2),
                        java.time.Duration.ofMinutes(5),
                        100),
                vectorWorkRepository,
                vectorSyncMetrics,
                ulidGenerator,
                TOKEN,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void currentListing(ListingDiscoveryEmbeddingSource source) {
        when(listingRepository.findOptionalById(LISTING_ID))
                .thenReturn(Optional.of(listing("ACTIVE", "APPROVED", 7)));
        when(listingRepository.findPublicListingById(LISTING_ID))
                .thenReturn(Optional.of(publicListing("Desk")));
        when(mediaRepository.findPublicImagesByListingId(LISTING_ID)).thenReturn(List.of());
        assertThat(builder.build(publicListing("Desk"), 7, List.of())).isEqualTo(source);
    }

    private ListingDiscoveryEmbeddingResultRequest result(
            ListingDiscoveryEmbeddingVectorCodec.CanonicalVector vector) {
        return new ListingDiscoveryEmbeddingResultRequest(
                "MARKETPLACE_LISTING_EMBEDDING_RESULT_V1",
                7,
                "MARKETPLACE_LISTING_DISCOVERY_V2",
                source().documentHash(),
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
                source().embeddingInputHash(),
                new ListingDiscoveryEmbeddingResultRequest.EmbeddingIdentity(
                        "openai",
                        "text-embedding-3-small",
                        1536),
                vector);
    }

    private ListingDiscoveryEmbeddingVectorCodec.CanonicalVector vector(double value) {
        ArrayNode values = new ObjectMapper().createArrayNode();
        for (int index = 0; index < 1536; index++) {
            values.add(value);
        }
        return ListingDiscoveryEmbeddingVectorCodec.encode(values);
    }

    private ListingDiscoveryEmbeddingSource source() {
        return builder.build(publicListing("Desk"), 7, List.of());
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

    private ListingDiscoveryEmbeddingReceipt receipt(
            ListingDiscoveryEmbeddingRequest request,
            ListingDiscoveryEmbeddingResultRequest result) {
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

    private PublicListingResponse publicListing(String title) {
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
