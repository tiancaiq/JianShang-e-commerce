package com.msb.ecom.product_service.service;

import com.msb.ecom.product_service.dto.InternalReportListingContext;
import com.msb.ecom.product_service.dto.InternalReportListingContext.EnforcementSummary;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.enforcement.EnforcementService;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class ReportListingContextService {
    private final ListingDraftRepository listings;
    private final ListingMediaRepository media;
    private final EnforcementService enforcement;
    private final Clock clock;

    @Transactional(readOnly = true)
    // Exposes only allow-listed Product-owned context for cross-domain report snapshots.
    public InternalReportListingContext context(String listingId) {
        ListingDraftResponse listing = listings.findOptionalById(listingId).orElseThrow(ListingNotFoundException::new);
        boolean reportable = listings.findPublicListingById(listingId).isPresent();
        return new InternalReportListingContext(
                listing.id(), listing.sellerType(), listing.individualSellerUserId(), listing.businessId(),
                listing.storeId(), listing.title(), listing.description(), listing.priceAmount(), listing.currency(),
                listing.categoryId(), listing.sku(), listing.status(),
                media.findPublicImagesByListingId(listingId).stream().map(image -> image.url()).toList(),
                clock.instant(), listing.version(), reportable,
                enforcement.evaluate(listingId, clock.instant()).stream()
                        .map(value -> new EnforcementSummary(value.actionType().name(), java.util.List.of(value.scope().name())))
                        .toList());
    }
}
