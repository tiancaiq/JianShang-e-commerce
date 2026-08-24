package com.msb.ecom.product_service.service;

import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingImageResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementService;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportListingContextServiceTests {
    @Mock ListingDraftRepository listings;
    @Mock ListingMediaRepository media;
    @Mock EnforcementService enforcement;
    @Mock Clock clock;
    @InjectMocks ReportListingContextService service;

    @Test
    void returnsAllowListedOwnerSnapshotPublicImageReferencesAndReadOnlyEnforcement() {
        String id = "01ARZ3NDEKTSV4RRFFQ69G5FCA";
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        ListingDraftResponse listing = mock(ListingDraftResponse.class);
        PublicListingImageResponse image = mock(PublicListingImageResponse.class);
        when(listings.findOptionalById(id)).thenReturn(Optional.of(listing));
        when(listings.findPublicListingById(id)).thenReturn(Optional.of(mock(PublicListingResponse.class)));
        when(listing.id()).thenReturn(id); when(listing.sellerType()).thenReturn("INDIVIDUAL");
        when(listing.individualSellerUserId()).thenReturn("01ARZ3NDEKTSV4RRFFQ69G5FCB");
        when(listing.title()).thenReturn("Snapshot title"); when(listing.description()).thenReturn("Description");
        when(listing.priceAmount()).thenReturn(new BigDecimal("10.00")); when(listing.currency()).thenReturn("USD");
        when(listing.categoryId()).thenReturn("01ARZ3NDEKTSV4RRFFQ69G5FCC"); when(listing.status()).thenReturn("ACTIVE");
        when(listing.version()).thenReturn(4L); when(media.findPublicImagesByListingId(id)).thenReturn(List.of(image));
        when(image.url()).thenReturn("/api/v1/public/listing-media/01IMAGE"); when(clock.instant()).thenReturn(now);
        when(enforcement.evaluate(id, now)).thenReturn(List.of(new EffectiveRestriction(
                Scope.LISTING_PUBLIC_VISIBILITY, ActionType.SUSPEND, "01ARZ3NDEKTSV4RRFFQ69G5FCD")));

        var result = service.context(id);

        assertThat(result.reportable()).isTrue();
        assertThat(result.title()).isEqualTo("Snapshot title");
        assertThat(result.imageReferences()).containsExactly("/api/v1/public/listing-media/01IMAGE");
        assertThat(result.enforcement()).singleElement().satisfies(value -> {
            assertThat(value.actionType()).isEqualTo("SUSPEND");
            assertThat(value.scopes()).containsExactly("LISTING_PUBLIC_VISIBILITY");
        });
    }
}
