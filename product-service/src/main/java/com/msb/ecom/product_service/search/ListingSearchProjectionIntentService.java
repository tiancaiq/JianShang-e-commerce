package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.service.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ListingSearchProjectionIntentService {

    private final ListingSearchProjectionSyncProperties properties;
    private final ListingSearchPromotionProperties promotionProperties;
    private final ListingDraftRepository listingRepository;
    private final ListingSearchProjectionWorkRepository workRepository;
    private final ListingSearchPromotionRepository promotionRepository;
    private final UlidGenerator ulidGenerator;

    @Transactional(propagation = Propagation.MANDATORY)
    // Captures only listing identity/version and the authoritative public-eligibility decision.
    public void recordCurrentState(String listingId, Instant occurredAt) {
        if (!properties.enabled()) {
            return;
        }
        promotionRepository.acquireMutationFenceShared(
                promotionProperties.effectiveLockWaitSeconds());
        ListingDraftResponse listing = listingRepository.findOptionalById(listingId)
                .orElseThrow(ListingNotFoundException::new);
        workRepository.insert(
                ulidGenerator.next(),
                listing.id(),
                listing.version(),
                isPubliclySearchable(listing) ? "UPSERT" : "DELETE",
                occurredAt);
    }

    static boolean isPubliclySearchable(ListingDraftResponse listing) {
        return ("INDIVIDUAL".equals(listing.sellerType())
                        && "ACTIVE".equals(listing.status())
                        && "APPROVED".equals(listing.moderationStatus()))
                || ("BUSINESS".equals(listing.sellerType())
                        && "ACTIVE".equals(listing.status())
                        && "BUSINESS_SELF_PUBLISHED".equals(listing.publicationSource()));
    }
}
