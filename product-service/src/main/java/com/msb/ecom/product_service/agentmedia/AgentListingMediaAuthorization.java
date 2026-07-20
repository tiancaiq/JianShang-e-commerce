package com.msb.ecom.product_service.agentmedia;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgentListingMediaAuthorization {

    static final int MAX_MEDIA_COUNT = 4;
    static final long MAX_MEDIA_BYTES = 10L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L;
    private static final Set<String> ALLOWED_LISTING_STATES = Set.of("DRAFT", "CHANGES_REQUESTED");
    private static final Set<String> ALLOWED_MEDIA_STATES =
            Set.of("NOT_SUBMITTED", "CHANGES_REQUESTED", "APPROVED");
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private final AgentListingMediaRepository repository;

    @Transactional(readOnly = true)
    // Captures one Product-owned authorization snapshot before any external storage read.
    public AuthorizedSelection authorize(
            String actorUserId,
            String listingId,
            List<String> requestedMediaIds) {
        if (requestedMediaIds == null
                || requestedMediaIds.isEmpty()
                || requestedMediaIds.size() > MAX_MEDIA_COUNT
                || requestedMediaIds.size() != new HashSet<>(requestedMediaIds).size()) {
            throw new AgentListingMediaRejectedException();
        }
        AgentListingMediaRepository.ListingSnapshot listing = repository.findListing(listingId)
                .filter(item -> "INDIVIDUAL".equals(item.sellerType()))
                .filter(item -> actorUserId.equals(item.individualSellerUserId()))
                .filter(item -> ALLOWED_LISTING_STATES.contains(item.status()))
                .orElseThrow(AgentListingMediaNotFoundException::new);

        List<AgentListingMediaRepository.MediaSnapshot> selected = new ArrayList<>();
        long declaredTotal = 0;
        for (String requestedMediaId : requestedMediaIds) {
            String mediaId = FixedLengthIds.requireTrimmed("Media ID", requestedMediaId, 26);
            AgentListingMediaRepository.MediaSnapshot media = repository.findMedia(listingId, mediaId)
                    .filter(item -> listingId.equals(item.listingId()))
                    .filter(item -> "INDIVIDUAL".equals(item.sellerType()))
                    .filter(item -> actorUserId.equals(item.individualSellerUserId()))
                    .filter(item -> "UPLOADED".equals(item.uploadStatus()))
                    .filter(item -> ALLOWED_MEDIA_STATES.contains(item.moderationStatus()))
                    .orElseThrow(AgentListingMediaNotFoundException::new);
            String contentType = normalizedContentType(media.contentType());
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)
                    || media.sizeBytes() < 1
                    || media.sizeBytes() > MAX_MEDIA_BYTES) {
                throw new AgentListingMediaRejectedException();
            }
            declaredTotal += media.sizeBytes();
            if (declaredTotal > MAX_TOTAL_BYTES) {
                throw new AgentListingMediaRejectedException();
            }
            selected.add(media);
        }
        return new AuthorizedSelection(listing, List.copyOf(selected));
    }

    private String normalizedContentType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthorizedSelection(
            AgentListingMediaRepository.ListingSnapshot listing,
            List<AgentListingMediaRepository.MediaSnapshot> media
    ) {
    }
}
