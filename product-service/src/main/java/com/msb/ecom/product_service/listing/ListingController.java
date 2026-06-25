package com.msb.ecom.product_service.listing;

import com.msb.ecom.product_service.listing.dto.CategoryResponse;
import com.msb.ecom.product_service.listing.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.listing.dto.ListingDraftResponse;
import com.msb.ecom.product_service.listing.dto.ListingMediaConfirmRequest;
import com.msb.ecom.product_service.listing.dto.ListingMediaResponse;
import com.msb.ecom.product_service.listing.dto.ListingMediaUploadRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return listingService.getActiveCategories();
    }

    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingDraftResponse createDraft(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateListingDraftRequest request) {
        if (jwt == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        return listingService.createDraft(jwt, request);
    }

    @PostMapping("/listings/{listingId}/media/upload-request")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingMediaResponse requestMediaUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String listingId,
            @Valid @RequestBody ListingMediaUploadRequest request) {
        if (jwt == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        return listingService.requestMediaUpload(jwt, listingId, request);
    }

    @PostMapping("/listings/{listingId}/media/{mediaId}/confirm")
    public ListingMediaResponse confirmMediaUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String listingId,
            @PathVariable String mediaId,
            @Valid @RequestBody ListingMediaConfirmRequest request) {
        if (jwt == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        return listingService.confirmMediaUpload(jwt, listingId, mediaId, request);
    }
}
