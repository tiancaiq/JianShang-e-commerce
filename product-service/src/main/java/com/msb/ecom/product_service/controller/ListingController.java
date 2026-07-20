package com.msb.ecom.product_service.controller;

import com.msb.ecom.common.web.http.IfMatchVersion;
import com.msb.ecom.product_service.dto.AdminActiveListingUpdateRequest;
import com.msb.ecom.product_service.dto.AdminListingModerationCaseDetailResponse;
import com.msb.ecom.product_service.dto.AdminListingModerationCaseResponse;
import com.msb.ecom.product_service.dto.AdminListingModerationSummaryResponse;
import com.msb.ecom.product_service.dto.AdminListingRemoveRequest;
import com.msb.ecom.product_service.service.ListingService;
import com.msb.ecom.product_service.dto.CategoryResponse;
import com.msb.ecom.product_service.dto.ChatListingEligibilityResponse;
import com.msb.ecom.product_service.dto.ChatTradeCompletionRequest;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchPageResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchRequest;
import com.msb.ecom.product_service.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.dto.ListingEngagementResponse;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.ListingImageResponse;
import com.msb.ecom.product_service.dto.ListingMediaConfirmRequest;
import com.msb.ecom.product_service.dto.ListingMediaResponse;
import com.msb.ecom.product_service.dto.ListingMediaUploadRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.dto.PublicListingSearchPageResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.UpdateListingImagesRequest;
import com.msb.ecom.product_service.search.ListingSearchRebuildResponse;
import com.msb.ecom.product_service.service.ListingMediaContent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    @PostMapping("/listings/{listingId}/visit")
    public ListingEngagementResponse recordListingVisit(@PathVariable String listingId) {
        return listingService.recordListingVisit(listingId);
    }

    @PostMapping("/listings/{listingId}/like")
    public ListingEngagementResponse likeListing(@PathVariable String listingId) {
        return listingService.likeListing(listingId);
    }

    @DeleteMapping("/listings/{listingId}/like")
    public ListingEngagementResponse unlikeListing(@PathVariable String listingId) {
        return listingService.unlikeListing(listingId);
    }

    @GetMapping("/listings/{listingId}/engagement/me")
    public ListingEngagementResponse myListingEngagement(@PathVariable String listingId) {
        return listingService.getMyListingEngagement(listingId);
    }

    @GetMapping("/internal/chat/listings/{listingId}/conversation-eligibility")
    public ChatListingEligibilityResponse chatListingEligibility(@PathVariable String listingId) {
        return listingService.chatListingEligibility(listingId);
    }

    @PostMapping("/internal/chat/listings/{listingId}/complete-trade")
    public ListingDraftResponse completeChatTrade(
            @PathVariable String listingId,
            @RequestBody ChatTradeCompletionRequest request) {
        return listingService.completeChatTrade(listingId, request);
    }

    @GetMapping("/public/listings")
    public List<PublicListingResponse> publicListings() {
        return listingService.getPublicListings();
    }

    @GetMapping("/users/me/liked-listings")
    public List<PublicListingResponse> myLikedListings() {
        return listingService.getMyLikedListings();
    }

    @GetMapping("/public/marketplace/listings/search")
    public PublicListingSearchPageResponse individualMarketplaceSearch(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return listingService.searchIndividualMarketplaceListings(new PublicListingSearchRequest(
                q,
                categoryId,
                condition,
                minPrice,
                maxPrice,
                city,
                county == null || county.isBlank() ? region : county,
                sort,
                cursor,
                limit));
    }

    @GetMapping("/public/stores/listings/search")
    public PublicListingSearchPageResponse businessStoreListingsSearch(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String condition,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return listingService.searchBusinessStoreListings(new PublicListingSearchRequest(
                q,
                categoryId,
                condition,
                minPrice,
                maxPrice,
                city,
                county == null || county.isBlank() ? region : county,
                sort,
                cursor,
                limit));
    }

    @GetMapping("/public/listing-media/{imageId}")
    public ResponseEntity<byte[]> publicListingMedia(@PathVariable String imageId) {
        ListingMediaContent content = listingService.publicListingMediaContent(imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }

    @GetMapping("/users/me/listings")
    public List<ListingDraftResponse> myListings() {
        return listingService.getCurrentIndividualSellerListings();
    }

    @GetMapping("/businesses/{businessId}/listings")
    public List<ListingDraftResponse> businessListings(@PathVariable String businessId) {
        return listingService.getBusinessListings(businessId);
    }

    @GetMapping("/businesses/{businessId}/store/items")
    public List<ListingDraftResponse> businessStoreItems(@PathVariable String businessId) {
        return listingService.getBusinessListings(businessId);
    }

    @GetMapping("/businesses/{businessId}/store/items/search")
    public BusinessStoreItemSearchPageResponse businessStoreItemsSearch(
            @PathVariable String businessId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return listingService.searchBusinessStoreItems(
                businessId,
                new BusinessStoreItemSearchRequest(q, status, cursor, limit));
    }

    @PostMapping("/businesses/{businessId}/store/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingDraftResponse createBusinessStoreItem(
            @PathVariable String businessId,
            @Valid @RequestBody CreateListingDraftRequest request) {
        return listingService.createBusinessStoreItemDraft(businessId, request);
    }

    @GetMapping("/businesses/{businessId}/store/items/{listingId}")
    public ListingDraftResponse businessStoreItem(
            @PathVariable String businessId,
            @PathVariable String listingId) {
        return listingService.getBusinessStoreItem(businessId, listingId);
    }

    @PatchMapping("/businesses/{businessId}/store/items/{listingId}")
    public ListingDraftResponse updateBusinessStoreItem(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody CreateListingDraftRequest request) {
        return listingService.updateBusinessStoreItemDraft(
                businessId,
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED),
                request);
    }

    @PostMapping("/businesses/{businessId}/store/items/{listingId}/publish")
    public ListingDraftResponse publishBusinessStoreItem(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return listingService.publishBusinessStoreItem(
                businessId,
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED));
    }

    @PostMapping("/businesses/{businessId}/store/items/{listingId}/pause")
    public ListingDraftResponse pauseBusinessStoreItem(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return listingService.pauseBusinessStoreItem(
                businessId,
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED));
    }

    @PostMapping("/businesses/{businessId}/store/items/{listingId}/relist")
    public ListingDraftResponse relistBusinessStoreItem(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return listingService.relistBusinessStoreItem(
                businessId,
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED));
    }

    @GetMapping("/admin/listings/moderation")
    public List<ListingDraftResponse> pendingReviewListings() {
        return listingService.getPendingReviewListings();
    }

    @GetMapping("/admin/listings/{listingId}")
    public ListingDraftResponse adminListing(@PathVariable String listingId) {
        return listingService.getAdminListing(listingId);
    }

    @GetMapping("/admin/listings/{listingId}/media/{mediaId}/content")
    public ResponseEntity<byte[]> adminListingMedia(
            @PathVariable String listingId,
            @PathVariable String mediaId) {
        ListingMediaContent content = listingService.adminListingMediaContent(listingId, mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }

    @PatchMapping("/admin/listings/{listingId}")
    public ListingDraftResponse updateActiveListingByAdmin(
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AdminActiveListingUpdateRequest request) {
        return listingService.updateActiveListingByAdmin(
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED),
                request);
    }

    @PostMapping("/admin/listings/{listingId}/remove")
    public ListingDraftResponse removeActiveListingByAdmin(
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AdminListingRemoveRequest request) {
        return listingService.removeActiveListingByAdmin(
                listingId,
                IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED),
                request);
    }

    @GetMapping("/admin/listings/moderation/summary")
    public AdminListingModerationSummaryResponse adminModerationSummary() {
        return listingService.adminModerationSummary();
    }

    @PostMapping("/admin/search/listings/rebuild")
    public ListingSearchRebuildResponse rebuildPublicListingSearchIndex() {
        return listingService.rebuildPublicListingSearchIndex();
    }

    @GetMapping("/admin/moderation/listing-cases")
    public List<AdminListingModerationCaseResponse> listingModerationCases(
            @RequestParam(name = "filter", required = false, defaultValue = "open") String filter,
            @RequestParam(name = "q", required = false) String query) {
        return listingService.getListingModerationCases(filter, query);
    }

    @GetMapping("/admin/moderation/listing-cases/{caseId}")
    public AdminListingModerationCaseDetailResponse listingModerationCaseDetail(@PathVariable String caseId) {
        return listingService.getListingModerationCaseDetail(caseId);
    }

    @PostMapping("/admin/moderation/listing-cases/{caseId}/claim")
    public AdminListingModerationCaseResponse claimListingModerationCase(
            @PathVariable String caseId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return listingService.claimListingModerationCase(caseId, parseVersion(ifMatch));
    }

    @PostMapping("/admin/moderation/listing-cases/{caseId}/release")
    public AdminListingModerationCaseResponse releaseListingModerationCase(
            @PathVariable String caseId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return listingService.releaseListingModerationCase(caseId, parseVersion(ifMatch));
    }

    @PostMapping("/admin/moderation/listing-cases/{caseId}/resolve")
    public AdminListingModerationCaseDetailResponse resolveListingModerationCase(
            @PathVariable String caseId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ListingModerationDecisionRequest request) {
        return listingService.resolveListingModerationCase(caseId, parseVersion(ifMatch), request);
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

    @PostMapping("/listings/{listingId}/close")
    public ListingDraftResponse closeListing(
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return listingService.closeListing(
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

    @PostMapping("/businesses/{businessId}/store/items/{listingId}/media/upload-request")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingMediaResponse requestBusinessStoreItemMediaUpload(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @Valid @RequestBody ListingMediaUploadRequest request) {
        return listingService.requestBusinessStoreItemMediaUpload(businessId, listingId, request);
    }

    @PostMapping("/listings/{listingId}/media/{mediaId}/confirm")
    public ListingMediaResponse confirmMediaUpload(
            @PathVariable String listingId,
            @PathVariable String mediaId,
            @Valid @RequestBody ListingMediaConfirmRequest request) {
        return listingService.confirmMediaUpload(listingId, mediaId, request);
    }

    @PostMapping("/businesses/{businessId}/store/items/{listingId}/media/{mediaId}/confirm")
    public ListingMediaResponse confirmBusinessStoreItemMediaUpload(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @PathVariable String mediaId,
            @Valid @RequestBody ListingMediaConfirmRequest request) {
        return listingService.confirmBusinessStoreItemMediaUpload(businessId, listingId, mediaId, request);
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

    @PutMapping(
            value = "/businesses/{businessId}/store/items/{listingId}/media/{mediaId}/content",
            consumes = {"image/jpeg", "image/png", "image/webp"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadBusinessStoreItemMediaContent(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @PathVariable String mediaId,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            @RequestBody byte[] bytes) {
        listingService.uploadBusinessStoreItemMediaContent(businessId, listingId, mediaId, contentType, bytes);
    }

    @GetMapping("/listings/{listingId}/media/{mediaId}/content")
    public ResponseEntity<byte[]> ownedListingMedia(
            @PathVariable String listingId,
            @PathVariable String mediaId) {
        ListingMediaContent content = listingService.ownedListingMediaContent(listingId, mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(content.bytes());
    }

    @PutMapping("/listings/{listingId}/images")
    public List<ListingImageResponse> updateListingImages(
            @PathVariable String listingId,
            @Valid @RequestBody UpdateListingImagesRequest request) {
        return listingService.updateListingImages(listingId, request);
    }

    @PutMapping("/businesses/{businessId}/store/items/{listingId}/images")
    public List<ListingImageResponse> updateBusinessStoreItemImages(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @Valid @RequestBody UpdateListingImagesRequest request) {
        return listingService.updateBusinessStoreItemImages(businessId, listingId, request);
    }

    private long parseVersion(String ifMatch) {
        return IfMatchVersion.parseRequired(ifMatch, LISTING_VERSION_REQUIRED);
    }

}
