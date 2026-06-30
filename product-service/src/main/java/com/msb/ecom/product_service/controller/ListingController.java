package com.msb.ecom.product_service.controller;

import com.msb.ecom.common.web.http.IfMatchVersion;
import com.msb.ecom.product_service.service.ListingService;
import com.msb.ecom.product_service.dto.CategoryResponse;
import com.msb.ecom.product_service.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.ListingImageResponse;
import com.msb.ecom.product_service.dto.ListingMediaConfirmRequest;
import com.msb.ecom.product_service.dto.ListingMediaResponse;
import com.msb.ecom.product_service.dto.ListingMediaUploadRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.UpdateListingImagesRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ListingController {

    private static final String LISTING_VERSION_REQUIRED = "If-Match must contain the current listing version.";

    private final ListingService listingService;

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return listingService.getActiveCategories();
    }

    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingDraftResponse createDraft(@Valid @RequestBody CreateListingDraftRequest request) {
        return listingService.createDraft(request);
    }

    @GetMapping("/listings/{listingId}")
    public ListingDraftResponse getOwnedDraft(@PathVariable String listingId) {
        return listingService.getOwnedListing(listingId);
    }

    @GetMapping("/public/listings/{listingId}")
    public PublicListingResponse getPublicListing(@PathVariable String listingId) {
        return listingService.getPublicListing(listingId);
    }

    @GetMapping("/public/listings")
    public List<PublicListingResponse> publicListings() {
        return listingService.getPublicListings();
    }

    @GetMapping("/public/listing-media/{imageId}")
    public ResponseEntity<Void> publicListingMedia(@PathVariable String imageId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(listingService.publicListingMediaReadUri(imageId))
                .build();
    }

    @GetMapping("/users/me/listings")
    public List<ListingDraftResponse> myListings() {
        return listingService.getCurrentIndividualSellerListings();
    }

    @GetMapping("/businesses/{businessId}/listings")
    public List<ListingDraftResponse> businessListings(@PathVariable String businessId) {
        return listingService.getBusinessListings(businessId);
    }

    @GetMapping("/admin/listings/moderation")
    public List<ListingDraftResponse> pendingReviewListings() {
        return listingService.getPendingReviewListings();
    }

    @PatchMapping("/listings/{listingId}")
    public ListingDraftResponse updateDraft(
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CreateListingDraftRequest request) {
        return listingService.updateDraft(
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED),
                request);
    }

    @PostMapping("/listings/{listingId}/submit")
    public ListingDraftResponse submitForReview(
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return listingService.submitForReview(
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED));
    }

    @PostMapping("/admin/listings/{listingId}/decision")
    public ListingModerationDecisionResponse decideListing(
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ListingModerationDecisionRequest request) {
        return listingService.decideListing(
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED),
                request);
    }

    @PostMapping("/listings/{listingId}/media/upload-request")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingMediaResponse requestMediaUpload(
            @PathVariable String listingId,
            @Valid @RequestBody ListingMediaUploadRequest request) {
        return listingService.requestMediaUpload(listingId, request);
    }

    @PostMapping("/listings/{listingId}/media/{mediaId}/confirm")
    public ListingMediaResponse confirmMediaUpload(
            @PathVariable String listingId,
            @PathVariable String mediaId,
            @Valid @RequestBody ListingMediaConfirmRequest request) {
        return listingService.confirmMediaUpload(listingId, mediaId, request);
    }

    @PutMapping(
            value = "/listings/{listingId}/media/{mediaId}/content",
            consumes = {"image/jpeg", "image/png", "image/webp"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadMediaContent(
            @PathVariable String listingId,
            @PathVariable String mediaId,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            @RequestBody byte[] bytes) {
        listingService.uploadMediaContent(listingId, mediaId, contentType, bytes);
    }

    @GetMapping("/listings/{listingId}/media/{mediaId}/content")
    public ResponseEntity<Void> ownedListingMedia(
            @PathVariable String listingId,
            @PathVariable String mediaId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(listingService.ownedListingMediaReadUri(listingId, mediaId))
                .build();
    }

    @PutMapping("/listings/{listingId}/images")
    public List<ListingImageResponse> updateListingImages(
            @PathVariable String listingId,
            @Valid @RequestBody UpdateListingImagesRequest request) {
        return listingService.updateListingImages(listingId, request);
    }

}
