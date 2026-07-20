package com.msb.ecom.product_service.agentmedia;

import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentListingMediaServiceTests {

    private static final String TOKEN = "test-agent-token";
    private static final String ACTOR_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAA";
    private static final String OTHER_ACTOR_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String MEDIA_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE";
    private static final String MEDIA_ID_2 = "01ARZ3NDEKTSV4RRFFQ69G5FAF";
    private static final byte[] PNG =
            "\u0089PNG\r\n\u001a\nsafe-product-image".getBytes(StandardCharsets.ISO_8859_1);

    @Mock
    AgentListingMediaRepository repository;

    @Mock
    ListingMediaStorage storage;

    @Mock
    AgentListingMediaMetrics metrics;

    AgentListingMediaService service;

    @BeforeEach
    void setUp() {
        service = new AgentListingMediaService(
                new AgentListingMediaAuthorization(repository),
                storage,
                metrics,
                TOKEN,
                true);
    }

    @Test
    void returnsOnlyVerifiedContentInRequestedOrder() {
        when(repository.findListing(LISTING_ID)).thenReturn(Optional.of(listing(ACTOR_ID, "DRAFT")));
        when(repository.findMedia(LISTING_ID, MEDIA_ID_2))
                .thenReturn(Optional.of(media(MEDIA_ID_2, "object-two", PNG, "APPROVED")));
        when(repository.findMedia(LISTING_ID, MEDIA_ID))
                .thenReturn(Optional.of(media(MEDIA_ID, "object-one", PNG, "NOT_SUBMITTED")));
        when(storage.readObject("object-two")).thenReturn(PNG);
        when(storage.readObject("object-one")).thenReturn(PNG);

        AgentListingMediaResponse response = service.readOwnedDraftMedia(
                TOKEN,
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID_2, MEDIA_ID),
                "corr-success");

        assertThat(response.schemaVersion()).isEqualTo("ai-list-owned-draft-media-v1");
        assertThat(response.listingId()).isEqualTo(LISTING_ID);
        assertThat(response.listingVersion()).isEqualTo("12");
        assertThat(response.eligibility()).isEqualTo("OWNED_DRAFT");
        assertThat(response.media())
                .extracting(AgentListingMediaResponse.Media::mediaId)
                .containsExactly(MEDIA_ID_2, MEDIA_ID);
        assertThat(response.media())
                .allSatisfy(item -> {
                    assertThat(item.contentType()).isEqualTo("image/png");
                    assertThat(item.byteSize()).isEqualTo(PNG.length);
                    assertThat(item.sha256()).isEqualTo(sha256(PNG));
                    assertThat(item.contentBase64()).doesNotContain("object", ACTOR_ID);
                });
    }

    @Test
    void disabledToolDoesNotReadProductRepositoriesOrStorage() {
        AgentListingMediaService disabled =
                new AgentListingMediaService(
                        new AgentListingMediaAuthorization(repository),
                        storage,
                        metrics,
                        TOKEN,
                        false);

        assertThatThrownBy(() -> disabled.readOwnedDraftMedia(
                TOKEN,
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID),
                "corr-disabled"))
                .isInstanceOf(AgentListingMediaUnavailableException.class);

        verifyNoInteractions(repository, storage);
    }

    @Test
    void serviceAuthenticationIsConstantBoundaryBeforeProductReads() {
        assertThatThrownBy(() -> service.readOwnedDraftMedia(
                "wrong-token",
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID),
                "corr-auth"))
                .isInstanceOf(ListingAuthorizationException.class);

        verifyNoInteractions(repository, storage);
    }

    @Test
    void crossActorAndNonDraftListingsAreHidden() {
        when(repository.findListing(LISTING_ID))
                .thenReturn(Optional.of(listing(OTHER_ACTOR_ID, "DRAFT")))
                .thenReturn(Optional.of(listing(ACTOR_ID, "ACTIVE")));

        assertThatThrownBy(() -> service.readOwnedDraftMedia(
                TOKEN,
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID),
                "corr-cross-actor"))
                .isInstanceOf(AgentListingMediaNotFoundException.class);
        assertThatThrownBy(() -> service.readOwnedDraftMedia(
                TOKEN,
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID),
                "corr-active"))
                .isInstanceOf(AgentListingMediaNotFoundException.class);

        verifyNoInteractions(storage);
    }

    @Test
    void crossListingPendingRejectedAndNonImageMediaAreDeniedBeforeStorage() {
        when(repository.findListing(LISTING_ID)).thenReturn(Optional.of(listing(ACTOR_ID, "DRAFT")));
        when(repository.findMedia(LISTING_ID, MEDIA_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(media(
                        MEDIA_ID,
                        "pending",
                        PNG,
                        "PENDING",
                        "UPLOADED",
                        "image/png",
                        sha256(PNG))))
                .thenReturn(Optional.of(media(
                        MEDIA_ID,
                        "pending-upload",
                        PNG,
                        "NOT_SUBMITTED",
                        "PENDING_UPLOAD",
                        "image/png",
                        sha256(PNG))))
                .thenReturn(Optional.of(media(
                        MEDIA_ID,
                        "text",
                        PNG,
                        "NOT_SUBMITTED",
                        "UPLOADED",
                        "text/plain",
                        sha256(PNG))));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> service.readOwnedDraftMedia(
                    TOKEN,
                    LISTING_ID,
                    request(ACTOR_ID, MEDIA_ID),
                    "corr-state"))
                    .isInstanceOfAny(
                            AgentListingMediaNotFoundException.class,
                            AgentListingMediaRejectedException.class);
        }

        verifyNoInteractions(storage);
    }

    @Test
    void actualSizeMimeAndDigestMustMatchProductMetadata() {
        when(repository.findListing(LISTING_ID)).thenReturn(Optional.of(listing(ACTOR_ID, "DRAFT")));
        AgentListingMediaRepository.MediaSnapshot snapshot =
                media(MEDIA_ID, "object", PNG, "NOT_SUBMITTED");
        when(repository.findMedia(LISTING_ID, MEDIA_ID)).thenReturn(Optional.of(snapshot));
        when(storage.readObject("object"))
                .thenReturn(new byte[]{1, 2, 3})
                .thenReturn("GIF89a".getBytes(StandardCharsets.US_ASCII))
                .thenReturn(PNG);

        assertRejected();
        assertRejected();

        AgentListingMediaRepository.MediaSnapshot wrongDigest = media(
                MEDIA_ID,
                "object",
                PNG,
                "NOT_SUBMITTED",
                "UPLOADED",
                "image/png",
                "0".repeat(64));
        when(repository.findMedia(LISTING_ID, MEDIA_ID)).thenReturn(Optional.of(wrongDigest));
        assertRejected();
    }

    @Test
    void duplicateMediaSelectionFailsBeforeAnyMediaRead() {
        assertThatThrownBy(() -> service.readOwnedDraftMedia(
                TOKEN,
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID, MEDIA_ID),
                "corr-duplicate"))
                .isInstanceOf(AgentListingMediaRejectedException.class);

        verify(repository, never()).findMedia(LISTING_ID, MEDIA_ID);
        verifyNoInteractions(storage);
    }

    @Test
    void unexpectedProductReadFailureMapsToBoundedUnavailable() {
        when(repository.findListing(LISTING_ID))
                .thenThrow(new IllegalStateException("database detail must not escape"));

        assertThatThrownBy(() -> service.readOwnedDraftMedia(
                TOKEN,
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID),
                "corr-product-failure"))
                .isInstanceOf(AgentListingMediaUnavailableException.class)
                .hasMessage("Listing media is temporarily unavailable.");

        verifyNoInteractions(storage);
    }

    private void assertRejected() {
        assertThatThrownBy(() -> service.readOwnedDraftMedia(
                TOKEN,
                LISTING_ID,
                request(ACTOR_ID, MEDIA_ID),
                "corr-integrity"))
                .isInstanceOf(AgentListingMediaRejectedException.class);
    }

    private AgentListingMediaRepository.ListingSnapshot listing(String actorId, String status) {
        return new AgentListingMediaRepository.ListingSnapshot(
                LISTING_ID,
                "INDIVIDUAL",
                actorId,
                status,
                12);
    }

    private AgentListingMediaRepository.MediaSnapshot media(
            String mediaId,
            String objectKey,
            byte[] bytes,
            String moderationStatus) {
        return media(
                mediaId,
                objectKey,
                bytes,
                moderationStatus,
                "UPLOADED",
                "image/png",
                sha256(bytes));
    }

    private AgentListingMediaRepository.MediaSnapshot media(
            String mediaId,
            String objectKey,
            byte[] bytes,
            String moderationStatus,
            String uploadStatus,
            String contentType,
            String checksum) {
        return new AgentListingMediaRepository.MediaSnapshot(
                mediaId,
                LISTING_ID,
                "INDIVIDUAL",
                ACTOR_ID,
                objectKey,
                contentType,
                bytes.length,
                checksum,
                uploadStatus,
                moderationStatus,
                7);
    }

    private AgentListingMediaRequest request(String actorId, String... mediaIds) {
        AgentListingMediaRequest request = new AgentListingMediaRequest();
        request.setSchemaVersion("ai-list-owned-draft-media-v1");
        request.setActorUserId(actorId);
        request.setMediaIds(List.of(mediaIds));
        return request;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
