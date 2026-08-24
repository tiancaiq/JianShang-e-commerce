package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.core.validation.TextInputs;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.catalog.ListingCatalogPolicyService;
import com.msb.ecom.product_service.catalog.ListingCatalogPolicyService.ValidatedCatalogValues;
import com.msb.ecom.product_service.model.BusinessSkuConflictException;
import com.msb.ecom.product_service.model.CategoryNotFoundException;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.model.ListingMediaNotFoundException;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import com.msb.ecom.product_service.model.ListingSellerType;
import com.msb.ecom.product_service.model.ListingVersionConflictException;
import com.msb.ecom.product_service.model.ModerationCaseNotFoundException;
import com.msb.ecom.product_service.model.ModerationCaseVersionConflictException;
import com.msb.ecom.product_service.knowledge.ListingKnowledgePublicationService;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.AuthServiceClient.BusinessMembershipAuthorization;
import com.msb.ecom.product_service.service.AuthServiceClient.IndividualSellerAuthorization;
import com.msb.ecom.product_service.repository.CategoryRepository;
import com.msb.ecom.product_service.repository.BusinessStoreItemSearchCriteria;
import com.msb.ecom.product_service.repository.BusinessStoreItemStatusCounts;
import com.msb.ecom.product_service.repository.ListingDraftInsert;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingDraftRepository.ListingOwnerSnapshot;
import com.msb.ecom.product_service.repository.ListingDraftUpdate;
import com.msb.ecom.product_service.repository.ListingEngagementRepository;
import com.msb.ecom.product_service.repository.ListingImageInsert;
import com.msb.ecom.product_service.repository.ListingMediaInsert;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.repository.ListingModerationDecisionInsert;
import com.msb.ecom.product_service.repository.ListingModerationDecisionRepository;
import com.msb.ecom.product_service.repository.ModerationCaseInsert;
import com.msb.ecom.product_service.repository.ModerationCaseEventInsert;
import com.msb.ecom.product_service.repository.ModerationCaseEventRepository;
import com.msb.ecom.product_service.repository.ModerationCaseRepository;
import com.msb.ecom.product_service.repository.ModerationCaseRepository.ModerationCaseEnsureResult;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;
import com.msb.ecom.product_service.search.ListingSearchDocument;
import com.msb.ecom.product_service.search.ListingSearchProperties;
import com.msb.ecom.product_service.search.ListingSearchProjectionIntentService;
import com.msb.ecom.product_service.search.ListingSearchRebuildResponse;
import com.msb.ecom.product_service.search.OpenSearchListingSearchClient;
import com.msb.ecom.product_service.security.AdminPermission;
import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingRequestService;
import com.msb.ecom.product_service.service.UlidGenerator;
import com.msb.ecom.product_service.dto.AdminActiveListingUpdateRequest;
import com.msb.ecom.product_service.dto.AdminListingModerationCaseDetailResponse;
import com.msb.ecom.product_service.dto.AdminListingModerationCaseResponse;
import com.msb.ecom.product_service.dto.AdminListingModerationSummaryResponse;
import com.msb.ecom.product_service.dto.AdminListingRemoveRequest;
import com.msb.ecom.product_service.dto.AdminTimelineEntryResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchPageResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchRequest;
import com.msb.ecom.product_service.dto.CategoryResponse;
import com.msb.ecom.product_service.dto.ChatListingEligibilityResponse;
import com.msb.ecom.product_service.dto.ChatTradeCompletionRequest;
import com.msb.ecom.product_service.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.dto.ListingEngagementResponse;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.ListingCatalogValuesResponse;
import com.msb.ecom.product_service.dto.ListingImageRequest;
import com.msb.ecom.product_service.dto.ListingImageResponse;
import com.msb.ecom.product_service.dto.ListingMediaConfirmRequest;
import com.msb.ecom.product_service.dto.ListingMediaResponse;
import com.msb.ecom.product_service.dto.ListingMediaUploadRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionResponse;
import com.msb.ecom.product_service.dto.PublicListingImageResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchPageResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.UpdateListingImagesRequest;
import com.msb.ecom.product_service.model.ListingMediaAccessDeniedException;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import com.msb.ecom.product_service.storage.ListingMediaStorageProperties;
import com.msb.ecom.product_service.storage.StorageObjectAccessDeniedException;
import com.msb.ecom.product_service.storage.StorageUploadTarget;
import com.msb.ecom.product_service.storage.StorageObjectNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListingService {

    static final int PUBLIC_BROWSE_LIMIT = 24;
    private static final int PUBLIC_BUSINESS_VISIBILITY_CANDIDATE_LIMIT = 500;
    static final int BUSINESS_STORE_ITEM_MANAGEMENT_LIMIT = 24;
    private static final String DEFAULT_INDIVIDUAL_SELLER_LABEL = "Marketplace seller";
    private static final String DEFAULT_BUSINESS_SELLER_LABEL = "Business seller";
    private static final Pattern SAFE_FILE_PART = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp");

    private final CategoryRepository categoryRepository;
    private final ListingCatalogPolicyService listingCatalogPolicyService;
    private final ListingDraftRepository listingDraftRepository;
    private final ListingEngagementRepository listingEngagementRepository;
    private final ListingMediaRepository listingMediaRepository;
    private final ListingModerationDecisionRepository listingModerationDecisionRepository;
    private final ModerationCaseRepository moderationCaseRepository;
    private final ModerationCaseEventRepository moderationCaseEventRepository;
    private final AuthServiceClient authServiceClient;
    private final UlidGenerator ulidGenerator;
    private final CurrentActorProvider currentActorProvider;
    private final ListingMediaStorage listingMediaStorage;
    private final ListingMediaStorageProperties mediaStorageProperties;
    private final ListingSearchProperties listingSearchProperties;
    private final ListingSearchProjectionIntentService listingSearchProjectionIntentService;
    private final OpenSearchListingSearchClient openSearchListingSearchClient;
    private final ListingKnowledgePublicationService listingKnowledgePublicationService;
    private final ListingDiscoveryEmbeddingRequestService listingDiscoveryEmbeddingRequestService;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getActiveCategories() {
        return categoryRepository.findActiveCategories();
    }

    @Transactional
    public ListingDraftResponse createDraft(CreateListingDraftRequest request) {
        ValidatedCatalogValues catalogValues = listingCatalogPolicyService.validateDraft(
                request.categoryId(), request.sellerType(), request.categoryRuleVersion(), request.attributes());

        ListingDraftResponse response = switch (request.sellerType()) {
            case INDIVIDUAL -> createIndividualDraft(request, catalogValues);
            case BUSINESS -> createBusinessDraft(request, catalogValues);
        };
        listingCatalogPolicyService.replaceAttributes(response.id(), catalogValues, Instant.now());

        log.info("Created listing draft id={} sellerType={}", response.id(), response.sellerType());
        return withSellerLabels(response);
    }

    @Transactional(readOnly = true)
    public ListingDraftResponse getOwnedListing(String listingId) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        return withSellerLabels(withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new)));
    }

    @Transactional(readOnly = true)
    public List<ListingDraftResponse> getCurrentIndividualSellerListings() {
        CurrentActor actor = currentActorProvider.currentActor();
        IndividualSellerAuthorization seller = authServiceClient.requireActiveIndividualSeller(actor.accessToken());
        return withSellerLabels(withImages(listingDraftRepository.findByIndividualSellerUserId(seller.userId())));
    }

    @Transactional(readOnly = true)
    public List<ListingDraftResponse> getBusinessListings(String businessId) {
        String normalizedBusinessId = normalizedRequiredId("Business ID", businessId);
        CurrentActor actor = currentActorProvider.currentActor();
        BusinessMembershipAuthorization membership =
                authServiceClient.requireBusinessListingPermission(actor.accessToken(), normalizedBusinessId);
        return withSellerLabels(withImages(listingDraftRepository.findByBusinessId(membership.businessId())));
    }

    @Transactional(readOnly = true)
    public ListingCatalogValuesResponse getOwnedListingCatalogValues(String listingId) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        return listingCatalogPolicyService.values(listing.id());
    }

    @Transactional(readOnly = true)
    // Returns one authorized business catalog page plus lifecycle counts for the seller management screen.
    public BusinessStoreItemSearchPageResponse searchBusinessStoreItems(
            String businessId,
            BusinessStoreItemSearchRequest request) {
        String normalizedBusinessId = normalizedRequiredId("Business ID", businessId);
        CurrentActor actor = currentActorProvider.currentActor();
        AuthServiceClient.BusinessStoreContextAuthorization context =
                authServiceClient.requireBusinessStoreContext(actor.accessToken(), normalizedBusinessId);
        BusinessStoreItemSearchCriteria criteria = BusinessStoreItemSearchRequests.criteria(request);
        int limit = BusinessStoreItemSearchRequests.normalizedLimit(
                request.limit(),
                BUSINESS_STORE_ITEM_MANAGEMENT_LIMIT);

        List<ListingDraftResponse> fetched = listingDraftRepository.searchBusinessStoreItems(
                context.businessId(),
                criteria,
                limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<ListingDraftResponse> rows = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore && !rows.isEmpty()
                ? BusinessStoreItemSearchRequests.encodeCursor(rows.get(rows.size() - 1))
                : null;
        BusinessStoreItemStatusCounts counts =
                listingDraftRepository.countBusinessStoreItemStatuses(context.businessId());

        return new BusinessStoreItemSearchPageResponse(
                withSellerLabels(withImages(rows)),
                new BusinessStoreItemSearchPageResponse.PageMetadata(nextCursor, hasMore),
                new BusinessStoreItemSearchPageResponse.StatusSummary(
                        counts.total(),
                        counts.draft(),
                        counts.active(),
                        counts.paused(),
                        counts.removed()));
    }

    @Transactional
    // Creates a business store item draft from the current approved store context; no public publishing occurs here.
    public ListingDraftResponse createBusinessStoreItemDraft(String businessId, CreateListingDraftRequest request) {
        return createDraft(businessStoreItemRequest(normalizedRequiredId("Business ID", businessId), request));
    }

    @Transactional(readOnly = true)
    public ListingDraftResponse getBusinessStoreItem(String businessId, String listingId) {
        String normalizedBusinessId = normalizedRequiredId("Business ID", businessId);
        ListingDraftResponse listing = getOwnedListing(listingId);
        if (!"BUSINESS".equals(listing.sellerType()) || !normalizedBusinessId.equals(listing.businessId())) {
            throw new ListingAuthorizationException("Listing belongs to another business.");
        }
        return listing;
    }

    @Transactional
    public ListingDraftResponse updateBusinessStoreItemDraft(
            String businessId,
            String listingId,
            long expectedVersion,
            CreateListingDraftRequest request) {
        ListingDraftResponse listing = requireBusinessStoreItemAccess(businessId, listingId, true);
        requireCurrentUserSellingCapability();
        if (!canBusinessSellerEdit(listing.status())) {
            throw new IllegalArgumentException("Pause an active store item before editing it.");
        }
        CreateListingDraftRequest normalizedRequest =
                businessStoreItemRequest(normalizedRequiredId("Business ID", businessId), request);
        ValidatedCatalogValues catalogValues = listingCatalogPolicyService.validateDraft(
                normalizedRequest.categoryId(), ListingSellerType.BUSINESS,
                normalizedRequest.categoryRuleVersion(), normalizedRequest.attributes());
        ListingDraftUpdate update = businessUpdate(
                new ListingOwnerSnapshot(
                        listing.id(),
                        ListingSellerType.BUSINESS,
                        null,
                        listing.businessId(),
                        listing.status()),
                normalizedRequest);
        Instant now = Instant.now();
        int updated;
        try {
            updated = "PAUSED".equals(listing.status())
                    ? listingDraftRepository.updatePausedBusinessStoreItem(
                            listing.id(), expectedVersion, update, catalogValues.ruleVersion(), now)
                    : listingDraftRepository.updateDraft(
                            listing.id(), expectedVersion, update, catalogValues.ruleVersion(), now);
        } catch (DuplicateKeyException exception) {
            throw duplicateBusinessSku(listing.businessId(), update.sku());
        }
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        listingCatalogPolicyService.replaceAttributes(listing.id(), catalogValues, now);

        log.info("Updated business store item listingId={} businessId={} status={}",
                listing.id(), listing.businessId(), listing.status());
        recordListingSearchProjection(listing.id(), now);
        return withSellerLabels(withImages(listingDraftRepository.findOptionalById(listing.id())
                .orElseThrow(ListingNotFoundException::new)));
    }

    @Transactional
    // Self-publishes a complete active-store item to the public /stores surface without admin moderation.
    public ListingDraftResponse publishBusinessStoreItem(String businessId, String listingId, long expectedVersion) {
        ListingDraftResponse listing = requireBusinessStoreItemAccess(businessId, listingId, true);
        requireCurrentUserSellingCapability();
        requireBusinessCapability(listing.businessId(), "BUSINESS_LISTING_PUBLICATION");
        if (!"DRAFT".equals(listing.status())) {
            throw new IllegalArgumentException("Only draft store items can be published.");
        }
        requirePublishableBusinessStoreItem(listing);
        listingCatalogPolicyService.validateSubmission(listing.id(), ListingSellerType.BUSINESS);

        Instant now = Instant.now();
        int updated = listingDraftRepository.publishBusinessStoreItem(listing.id(), expectedVersion, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        recordListingSearchProjection(listing.id(), now);
        log.info("Published business store item listingId={} businessId={}", listing.id(), listing.businessId());
        return withSellerLabels(withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new)));
    }

    @Transactional
    // Pauses a self-published store item so it no longer appears on /stores.
    public ListingDraftResponse pauseBusinessStoreItem(String businessId, String listingId, long expectedVersion) {
        ListingDraftResponse listing = requireBusinessStoreItemAccess(businessId, listingId, false);
        requireCurrentUserSellingCapability();
        Instant now = Instant.now();
        int updated = listingDraftRepository.pauseBusinessStoreItem(listing.id(), expectedVersion, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        recordListingSearchProjection(listing.id(), now);
        log.info("Paused business store item listingId={} businessId={}", listing.id(), listing.businessId());
        return withSellerLabels(withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new)));
    }

    @Transactional
    // Relists a paused self-published store item while preserving its original published timestamp.
    public ListingDraftResponse relistBusinessStoreItem(String businessId, String listingId, long expectedVersion) {
        ListingDraftResponse listing = requireBusinessStoreItemAccess(businessId, listingId, true);
        requireCurrentUserSellingCapability();
        requireBusinessCapability(listing.businessId(), "BUSINESS_LISTING_PUBLICATION");
        if (!"PAUSED".equals(listing.status())) {
            throw new IllegalArgumentException("Only paused store items can be relisted.");
        }
        requirePublishableBusinessStoreItem(listing);
        listingCatalogPolicyService.validateSubmission(listing.id(), ListingSellerType.BUSINESS);

        Instant now = Instant.now();
        int updated = listingDraftRepository.relistBusinessStoreItem(listing.id(), expectedVersion, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        recordListingSearchProjection(listing.id(), now);
        log.info("Relisted business store item listingId={} businessId={}", listing.id(), listing.businessId());
        return withSellerLabels(withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new)));
    }

    @Transactional(readOnly = true)
    // Public detail exposes only approved active listing data and safe image metadata.
    public PublicListingResponse getPublicListing(String listingId) {
        PublicListingResponse listing = listingDraftRepository.findPublicListingById(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new);
        return withPublicSellerLabels(List.of(publicListingWithImagesAndNotice(listing))).get(0);
    }

    @Transactional(readOnly = true)
    // Chat creation needs owner IDs, but only for active approved individual listings.
    public ChatListingEligibilityResponse chatListingEligibility(String listingId) {
        ChatListingEligibilityResponse eligibility = listingDraftRepository
                .findChatListingEligibility(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new);
        if (!"INDIVIDUAL".equals(eligibility.sellerType()) || eligibility.sellerUserId() == null) {
            throw new IllegalArgumentException("Only individual listings can start MVP chat.");
        }
        List<PublicListingImageResponse> images = listingMediaRepository.findPublicImagesByListingId(eligibility.listingId());
        String thumbnailUrl = images.isEmpty() ? null : images.get(0).url();
        return new ChatListingEligibilityResponse(
                eligibility.listingId(),
                true,
                eligibility.sellerType(),
                eligibility.sellerUserId(),
                eligibility.quantity(),
                eligibility.title(),
                eligibility.publicCity(),
                eligibility.publicRegion(),
                thumbnailUrl,
                transactionNotice(eligibility.sellerType()));
    }

    @Transactional
    // Chat-service closes an individual listing only after buyer confirmation in the fixed listing conversation.
    public ListingDraftResponse completeChatTrade(String listingId, ChatTradeCompletionRequest request) {
        String normalizedListingId = normalizedRequiredId("Listing ID", listingId);
        String sellerUserId = normalizedRequiredId("Seller user ID", request == null ? null : request.sellerUserId());
        normalizedRequiredId("Buyer user ID", request == null ? null : request.buyerUserId());
        normalizedRequiredId("Conversation ID", request == null ? null : request.conversationId());

        ListingDraftResponse listing = listingDraftRepository.findOptionalById(normalizedListingId)
                .orElseThrow(ListingNotFoundException::new);
        if (!"INDIVIDUAL".equals(listing.sellerType()) || !sellerUserId.equals(listing.individualSellerUserId())) {
            throw new ListingAuthorizationException("Listing belongs to another seller.");
        }
        int quantitySold = request.quantitySold() == null ? 1 : request.quantitySold();
        if (quantitySold < 1 || quantitySold > listing.quantity()) {
            throw new IllegalArgumentException("Quantity sold must be between 1 and listing quantity.");
        }
        if ("CLOSED".equals(listing.status())) {
            return withSellerLabels(withImages(listing));
        }
        // Buyer confirmation is the individual-sale commitment boundary. Replays of an
        // already closed trade stay readable, while a new commitment requires the
        // listing owner's current selling capability before Product changes state.
        requireSellingCapability(sellerUserId);
        if (!"ACTIVE".equals(listing.status()) || !"APPROVED".equals(listing.moderationStatus())) {
            throw new IllegalArgumentException("Only active approved individual listings can be completed from chat.");
        }

        Instant now = Instant.now();
        int updated = listingDraftRepository.closeActiveIndividualListingFromChat(normalizedListingId, sellerUserId, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        ListingDraftResponse updatedListing = reconcileListingKnowledge(listing, now);
        recordListingSearchProjection(normalizedListingId, now);
        log.info("Closed listing from chat completion listingId={} sellerUserId={} conversationId={} quantitySold={}",
                normalizedListingId, sellerUserId, request.conversationId(), quantitySold);
        return withSellerLabels(withImages(updatedListing));
    }

    @Transactional(readOnly = true)
    // Public browse is intentionally fixed-size until SEARCH-03 adds cursor pagination.
    public List<PublicListingResponse> getPublicListings() {
        List<PublicListingResponse> listings = visiblePublicListings(
                listingDraftRepository.findPublicListings(PUBLIC_BROWSE_LIMIT)).stream()
                .map(this::publicListingWithImagesAndNotice)
                .toList();
        return withPublicSellerLabels(listings);
    }

    @Transactional(readOnly = true)
    // Returns the current account's active likes using the same safe public listing projection as browse.
    public List<PublicListingResponse> getMyLikedListings() {
        List<PublicListingResponse> listings = listingDraftRepository
                .findPublicLikedListingsForUser(currentUserId(), PUBLIC_BROWSE_LIMIT)
                .stream()
                .toList();
        listings = visiblePublicListings(listings)
                .stream()
                .map(this::publicListingWithImagesAndNotice)
                .toList();
        return withPublicSellerLabels(listings);
    }

    @Transactional(readOnly = true)
    // SEARCH-00 keeps individual marketplace discovery separate from business storefront browse.
    public List<PublicListingResponse> searchIndividualMarketplaceListings() {
        return getPublicListingsBySellerType("INDIVIDUAL");
    }

    @Transactional(readOnly = true)
    // SEARCH-01A applies marketplace filters only to approved active individual listings.
    public PublicListingSearchPageResponse searchIndividualMarketplaceListings(PublicListingSearchRequest request) {
        PublicListingSearchCriteria criteria = PublicListingSearchRequests.criteria(request);
        return pagedPublicListings(
                "INDIVIDUAL",
                criteria,
                PublicListingSearchRequests.normalizedLimit(request.limit(), PUBLIC_BROWSE_LIMIT));
    }

    private PublicListingSearchPageResponse pagedPublicListings(
            String sellerType,
            PublicListingSearchCriteria criteria,
            int limit) {
        List<PublicListingResponse> fetched = searchPublicListingRows(sellerType, criteria, limit + 1).stream()
                .map(this::publicListingWithImagesAndNotice)
                .toList();
        boolean hasMore = fetched.size() > limit;
        List<PublicListingResponse> listings = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore && !listings.isEmpty()
                ? PublicListingSearchRequests.encodeCursor(criteria.sort(), listings.get(listings.size() - 1))
                : null;
        return new PublicListingSearchPageResponse(
                withPublicSellerLabels(listings),
                new PublicListingSearchPageResponse.PageMetadata(nextCursor, hasMore));
    }

    private List<PublicListingResponse> searchPublicListingRows(
            String sellerType,
            PublicListingSearchCriteria criteria,
            int limit) {
        if (!listingSearchProperties.openSearchEnabled() || "BUSINESS".equals(sellerType)) {
            return visibleBusinessStoreListings(
                    sellerType,
                    listingDraftRepository.searchPublicListingsBySellerType(sellerType, criteria, limit));
        }
        List<String> listingIds = openSearchListingSearchClient.searchIds(sellerType, criteria, limit);
        // OpenSearch only chooses candidate IDs; MySQL still revalidates public visibility before response.
        return visibleBusinessStoreListings(sellerType, listingDraftRepository.findPublicListingsByIds(listingIds));
    }

    @Transactional(readOnly = true)
    // SEARCH-00 keeps business storefront discovery separate from individual marketplace search.
    public List<PublicListingResponse> searchBusinessStoreListings() {
        return getPublicListingsBySellerType("BUSINESS");
    }

    @Transactional(readOnly = true)
    // SEARCH-01B applies storefront filters only to approved active business listings.
    public PublicListingSearchPageResponse searchBusinessStoreListings(PublicListingSearchRequest request) {
        PublicListingSearchCriteria criteria = businessStoreSearchCriteria(PublicListingSearchRequests.criteria(request));
        return pagedPublicListings(
                "BUSINESS",
                criteria,
                PublicListingSearchRequests.normalizedLimit(request.limit(), PUBLIC_BROWSE_LIMIT));
    }

    private List<PublicListingResponse> getPublicListingsBySellerType(String sellerType) {
        List<PublicListingResponse> listings = visibleBusinessStoreListings(
                        sellerType,
                        listingDraftRepository.findPublicListingsBySellerType(sellerType, PUBLIC_BROWSE_LIMIT))
                .stream()
                .map(this::publicListingWithImagesAndNotice)
                .toList();
        return withPublicSellerLabels(listings);
    }

    private PublicListingSearchCriteria businessStoreSearchCriteria(PublicListingSearchCriteria criteria) {
        try {
            Set<String> candidateBusinessIds = listingDraftRepository.findSelfPublishedBusinessIds(
                    PUBLIC_BUSINESS_VISIBILITY_CANDIDATE_LIMIT);
            if (candidateBusinessIds.isEmpty()) {
                return criteria.withVisibleBusinessIds(List.of()).withBusinessIds(List.of());
            }
            List<AuthServiceClient.PublicBusinessStoreSearchResult> visibleStores = authServiceClient
                    .searchPublicBusinessStores(null, candidateBusinessIds, Set.of());
            List<String> visibleBusinessIds = (visibleStores == null
                    ? List.<AuthServiceClient.PublicBusinessStoreSearchResult>of()
                    : visibleStores)
                    .stream()
                    .map(AuthServiceClient.PublicBusinessStoreSearchResult::businessId)
                    .filter(this::hasText)
                    .distinct()
                    .toList();
            PublicListingSearchCriteria visibleCriteria = criteria.withVisibleBusinessIds(visibleBusinessIds);
            if (hasText(criteria.city()) || hasText(criteria.county())) {
                visibleCriteria = visibleCriteria
                        .withVisibleBusinessIds(businessIdsMatchingPublicLocation(visibleBusinessIds, criteria))
                        .withoutLocationFilters();
            }
            if (!hasText(criteria.keyword())) {
                return visibleCriteria;
            }
            List<AuthServiceClient.PublicBusinessStoreSearchResult> matchedStores = authServiceClient
                    .searchPublicBusinessStores(criteria.keyword(), Set.of(), Set.of());
            List<String> matchedBusinessIds = (matchedStores == null
                    ? List.<AuthServiceClient.PublicBusinessStoreSearchResult>of()
                    : matchedStores)
                    .stream()
                    .map(AuthServiceClient.PublicBusinessStoreSearchResult::businessId)
                    .filter(this::hasText)
                    .distinct()
                    .toList();
            return visibleCriteria.withBusinessIds(matchedBusinessIds);
        } catch (ListingAuthorizationException exception) {
            log.warn("Auth-service public business store visibility lookup failed; hiding business search results.");
            return criteria.withVisibleBusinessIds(List.of()).withBusinessIds(List.of());
        } catch (RuntimeException exception) {
            log.warn("Auth-service public business store visibility lookup unavailable; hiding business search results.");
            return criteria.withVisibleBusinessIds(List.of()).withBusinessIds(List.of());
        }
    }

    // Applies storefront location filters to auth-owned public store metadata, which is also shown on listing cards.
    private List<String> businessIdsMatchingPublicLocation(
            List<String> visibleBusinessIds,
            PublicListingSearchCriteria criteria) {
        if (visibleBusinessIds.isEmpty()) {
            return List.of();
        }
        AuthServiceClient.AdminIdentityLabels labels = authServiceClient.lookupPublicSellerLabels(
                Set.of(),
                new LinkedHashSet<>(visibleBusinessIds));
        Map<String, AuthServiceClient.BusinessIdentityLabel> businesses = businessIdentityMap(labels);
        return visibleBusinessIds.stream()
                .filter(businessId -> publicStoreLocationMatches(
                        businesses.get(businessId),
                        criteria.city(),
                        criteria.county()))
                .toList();
    }

    private boolean publicStoreLocationMatches(
            AuthServiceClient.BusinessIdentityLabel business,
            String city,
            String region) {
        return business != null
                && publicLocationPartMatches(city, business.publicCity())
                && publicLocationPartMatches(region, business.publicRegion());
    }

    private boolean publicLocationPartMatches(String requested, String actual) {
        if (!hasText(requested)) {
            return true;
        }
        String normalizedActual = TextInputs.collapseWhitespaceToNull(actual);
        return normalizedActual != null && requested.equalsIgnoreCase(normalizedActual);
    }

    private List<PublicListingResponse> visibleBusinessStoreListings(
            String sellerType,
            List<PublicListingResponse> listings) {
        if (!"BUSINESS".equals(sellerType) || listings.isEmpty()) {
            return listings;
        }
        Set<String> businessIds = collectSellerIds(
                listings,
                listing -> "BUSINESS".equals(listing.sellerType()),
                PublicListingResponse::sellerId);
        if (businessIds.isEmpty()) {
            return List.of();
        }
        try {
            List<AuthServiceClient.PublicBusinessStoreSearchResult> stores =
                    authServiceClient.searchPublicBusinessStores(null, businessIds, Set.of());
            Set<String> visibleBusinessIds = (stores == null ? List.<AuthServiceClient.PublicBusinessStoreSearchResult>of() : stores)
                    .stream()
                    .map(AuthServiceClient.PublicBusinessStoreSearchResult::businessId)
                    .filter(this::hasText)
                    .collect(Collectors.toCollection(HashSet::new));
            return listings.stream()
                    .filter(listing -> visibleBusinessIds.contains(listing.sellerId()))
                    .toList();
        } catch (ListingAuthorizationException exception) {
            log.warn("Auth-service public business store visibility lookup failed; hiding business search results.");
            return List.of();
        } catch (RuntimeException exception) {
            log.warn("Auth-service public business store visibility lookup unavailable; hiding business search results.");
            return List.of();
        }
    }

    private List<PublicListingResponse> visiblePublicListings(List<PublicListingResponse> listings) {
        Set<String> visibleBusinessIds = visibleBusinessIdsFor(listings);
        return listings.stream()
                .filter(listing -> visiblePublicListing(listing, visibleBusinessIds))
                .toList();
    }

    private boolean visiblePublicListing(PublicListingResponse listing, Set<String> visibleBusinessIds) {
        return !"BUSINESS".equals(listing.sellerType()) || visibleBusinessIds.contains(listing.sellerId());
    }

    private Set<String> visibleBusinessIdsFor(List<PublicListingResponse> listings) {
        Set<String> businessIds = collectSellerIds(
                listings,
                listing -> "BUSINESS".equals(listing.sellerType()),
                PublicListingResponse::sellerId);
        if (businessIds.isEmpty()) {
            return Set.of();
        }
        try {
            List<AuthServiceClient.PublicBusinessStoreSearchResult> stores =
                    authServiceClient.searchPublicBusinessStores(null, businessIds, Set.of());
            return (stores == null ? List.<AuthServiceClient.PublicBusinessStoreSearchResult>of() : stores)
                    .stream()
                    .map(AuthServiceClient.PublicBusinessStoreSearchResult::businessId)
                    .filter(this::hasText)
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (ListingAuthorizationException exception) {
            log.warn("Auth-service public business store visibility lookup failed; hiding business browse results.");
            return Set.of();
        } catch (RuntimeException exception) {
            log.warn("Auth-service public business store visibility lookup unavailable; hiding business browse results.");
            return Set.of();
        }
    }

    private PublicListingResponse publicListingWithImagesAndNotice(PublicListingResponse listing) {
        return new PublicListingResponse(
                listing.id(),
                listing.sellerType(),
                listing.sellerId(),
                listing.sellerDisplayName(),
                listing.sellerAvatarUrl(),
                listing.storeId(),
                listing.storeSlug(),
                listing.storeName(),
                listing.businessVerified(),
                listing.categoryId(),
                listing.categorySlug(),
                listing.categoryName(),
                listing.title(),
                listing.description(),
                listing.condition(),
                listing.conditionNotes(),
                listing.priceAmount(),
                listing.currency(),
                listing.negotiable(),
                listing.quantity(),
                listing.publicCity(),
                listing.publicRegion(),
                listing.publishedAt(),
                transactionNotice(listing.sellerType()),
                listing.visitCount(),
                listing.likeCount(),
                listingMediaRepository.findPublicImagesByListingId(listing.id()));
    }

    @Transactional
    // Records at most one public visit per authenticated account without changing the listing aggregate version.
    public ListingEngagementResponse recordListingVisit(String listingId) {
        PublicListingResponse listing = publicListingForEngagement(listingId);
        String userId = currentUserId();
        Instant now = Instant.now();
        listingEngagementRepository.recordVisit(listing.id(), userId, now);
        return listingEngagementRepository.findEngagement(listing.id(), userId);
    }

    @Transactional
    // Likes are account-scoped and idempotent; every authenticated account can like a public listing once.
    public ListingEngagementResponse likeListing(String listingId) {
        PublicListingResponse listing = publicListingForEngagement(listingId);
        String userId = currentUserId();
        Instant now = Instant.now();
        listingEngagementRepository.activateLike(listing.id(), userId, now);
        return listingEngagementRepository.findEngagement(listing.id(), userId);
    }

    @Transactional
    public ListingEngagementResponse unlikeListing(String listingId) {
        PublicListingResponse listing = publicListingForEngagement(listingId);
        String userId = currentUserId();
        listingEngagementRepository.deactivateLike(listing.id(), userId, Instant.now());
        return listingEngagementRepository.findEngagement(listing.id(), userId);
    }

    @Transactional(readOnly = true)
    public ListingEngagementResponse getMyListingEngagement(String listingId) {
        PublicListingResponse listing = publicListingForEngagement(listingId);
        return listingEngagementRepository.findEngagement(listing.id(), currentUserId());
    }

    @Transactional
    public ListingDraftResponse updateDraft(
            String listingId,
            long expectedVersion,
            CreateListingDraftRequest request) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        requireCurrentUserSellingCapability();
        ListingDraftResponse before = listingDraftRepository.findOptionalById(listing.id())
                .orElseThrow(ListingNotFoundException::new);
        if (listing.sellerType() == ListingSellerType.BUSINESS && !"DRAFT".equals(listing.status())) {
            throw new IllegalArgumentException(
                    "Use the business store item route to edit paused items; active items must be paused first.");
        }
        if (!canSellerEdit(listing.status())) {
            throw new IllegalArgumentException("Listing cannot be edited in its current state.");
        }
        if (request.sellerType() != listing.sellerType()) {
            throw new IllegalArgumentException("Listing seller type cannot be changed.");
        }
        ValidatedCatalogValues catalogValues = listingCatalogPolicyService.validateDraft(
                request.categoryId(), listing.sellerType(), request.categoryRuleVersion(), request.attributes());

        ListingDraftUpdate update = switch (listing.sellerType()) {
            case INDIVIDUAL -> individualUpdate(listing, request);
            case BUSINESS -> businessUpdate(listing, request);
        };

        Instant now = Instant.now();
        int updated;
        try {
            updated = listingDraftRepository.updateDraft(
                    listing.id(), expectedVersion, update, catalogValues.ruleVersion(), now);
        } catch (DuplicateKeyException exception) {
            if (listing.sellerType() == ListingSellerType.BUSINESS) {
                throw duplicateBusinessSku(listing.businessId(), update.sku());
            }
            throw exception;
        }
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }

        listingCatalogPolicyService.replaceAttributes(listing.id(), catalogValues, now);
        log.info("Updated listing draft id={} sellerType={}", listing.id(), listing.sellerType());
        ListingDraftResponse updatedListing = reconcileListingKnowledge(before, now);
        recordListingSearchProjection(listing.id(), now);
        return withSellerLabels(withImages(updatedListing));
    }

    @Transactional
    // Seller close removes a listing from any public/moderation queue without deleting its audit history.
    public ListingDraftResponse closeListing(String listingId, long expectedVersion) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        requireCurrentUserSellingCapability();
        ListingDraftResponse before = listingDraftRepository.findOptionalById(listing.id())
                .orElseThrow(ListingNotFoundException::new);
        if (!canSellerClose(listing.status())) {
            throw new IllegalArgumentException("Only draft, pending-review, or active listings can be closed.");
        }

        Instant now = Instant.now();
        int updated = listingDraftRepository.closeListing(listing.id(), expectedVersion, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }

        log.info("Closed listing id={} sellerType={}", listing.id(), listing.sellerType());
        ListingDraftResponse updatedListing = reconcileListingKnowledge(before, now);
        recordListingSearchProjection(listing.id(), now);
        return withSellerLabels(withImages(updatedListing));
    }

    @Transactional
    public ListingDraftResponse submitForReview(String listingId, long expectedVersion) {
        OwnedListingSubmission submission = ownedListingSubmission(normalizedRequiredId("Listing ID", listingId));
        requireSellingCapability(submission.submittedByUserId());
        ListingOwnerSnapshot listing = submission.listing();
        if (listing.sellerType() == ListingSellerType.BUSINESS) {
            requireBusinessCapability(listing.businessId(), "BUSINESS_LISTING_PUBLICATION");
        }
        if (!"DRAFT".equals(listing.status())) {
            throw new IllegalArgumentException("Save listing changes before submitting for review.");
        }
        if (!listingMediaRepository.hasAttachedUploadedImage(listing.id())) {
            throw new IllegalArgumentException("At least one attached image is required before review submission.");
        }
        listingCatalogPolicyService.validateSubmission(listing.id(), listing.sellerType());

        Instant now = Instant.now();
        int updated = listingDraftRepository.submitForReview(listing.id(), expectedVersion, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        listingMediaRepository.markImagesPendingReview(listing.id(), Timestamp.from(now));
        ModerationCaseEnsureResult moderationCase = moderationCaseRepository.createOrReuseListingReviewCase(
                new ModerationCaseInsert(
                        ulidGenerator.next(),
                        listing.id(),
                        listing.sellerType(),
                        listing.individualSellerUserId(),
                        listing.businessId(),
                        submission.submittedByUserId(),
                        now));
        recordModerationCaseEvent(new ModerationCaseEventInsert(
                ulidGenerator.next(),
                moderationCase.id(),
                listing.id(),
                moderationCase.created() ? "CASE_CREATED" : "CASE_REOPENED",
                submission.submittedByUserId(),
                moderationCase.previousState(),
                "OPEN",
                moderationCase.previousAssignedAdminUserId(),
                null,
                null,
                currentCorrelationId(),
                now));

        log.info("Submitted listing for review id={} sellerType={} moderationCaseId={} moderationCaseCreated={} submittedByUserId={}",
                listing.id(), listing.sellerType(), moderationCase.id(), moderationCase.created(),
                submission.submittedByUserId());
        recordListingSearchProjection(listing.id(), now);
        return withSellerLabels(withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new)));
    }

    @Transactional(readOnly = true)
    // Admin review queue is scoped to submitted listings that still need a platform decision.
    public List<ListingDraftResponse> getPendingReviewListings() {
        requireAdminPermission(AdminPermission.LISTING_MODERATION_READ);
        return withSellerLabels(withImages(listingDraftRepository.findPendingReview()));
    }

    @Transactional(readOnly = true)
    // Lets platform admins inspect active listings before live marketplace edits/removal.
    public ListingDraftResponse getAdminListing(String listingId) {
        requireAdminPermission(AdminPermission.LISTING_MODERATION_READ);
        return withSellerLabels(withImages(listingDraftRepository.findOptionalById(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new)));
    }

    @Transactional
    // Applies a limited admin edit to an active approved listing and records reviewer history.
    public ListingDraftResponse updateActiveListingByAdmin(
            String listingId,
            long expectedVersion,
            AdminActiveListingUpdateRequest request) {
        AuthServiceClient.PlatformAdminAuthorization admin = requireAdminPermission(AdminPermission.LISTING_EDIT);
        String normalizedListingId = normalizedRequiredId("Listing ID", listingId);
        ListingDraftResponse listing = activeApprovedListing(normalizedListingId);
        if (!categoryRepository.activeCategoryExists(request.categoryId())) {
            throw new CategoryNotFoundException();
        }

        ListingDraftUpdate update = adminActiveListingUpdate(listing, request);
        Instant now = Instant.now();
        int updated = listingDraftRepository.updateActiveListingByAdmin(
                listing.id(),
                expectedVersion,
                update,
                now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        ListingDraftResponse updatedListing = reconcileListingKnowledge(listing, now);
        recordAdminListingAction(
                listing.id(), null, "ADMIN_EDIT", request.reason(), admin.userId(), expectedVersion + 1,
                listing.status() + "/" + listing.moderationStatus(), "ACTIVE/APPROVED", now);
        log.info("Admin edited active listing listingId={} adminUserId={}", listing.id(), admin.userId());
        recordListingSearchProjection(listing.id(), now);
        return withSellerLabels(withImages(updatedListing));
    }

    @Transactional
    // Removes an active approved listing from public marketplace visibility without deleting history.
    public ListingDraftResponse removeActiveListingByAdmin(
            String listingId,
            long expectedVersion,
            AdminListingRemoveRequest request) {
        AuthServiceClient.PlatformAdminAuthorization admin = requireAdminPermission(AdminPermission.LISTING_REMOVE);
        ListingDraftResponse listing = activePublicListing(normalizedRequiredId("Listing ID", listingId));
        Instant now = Instant.now();
        int updated = listingDraftRepository.removeActiveListingByAdmin(listing.id(), expectedVersion, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        ListingDraftResponse updatedListing = reconcileListingKnowledge(listing, now);
        recordAdminListingAction(
                listing.id(), null, "ADMIN_REMOVE", request.reason(), admin.userId(), expectedVersion + 1,
                listing.status() + "/" + listing.moderationStatus(), "REMOVED_BY_ADMIN/APPROVED", now);
        log.info("Admin removed active listing listingId={} adminUserId={}", listing.id(), admin.userId());
        recordListingSearchProjection(listing.id(), now);
        return withSellerLabels(withImages(updatedListing));
    }

    // Rebuilds the derived OpenSearch listing projection from authoritative MySQL rows.
    public ListingSearchRebuildResponse rebuildPublicListingSearchIndex() {
        requirePlatformAdmin();
        if (!listingSearchProperties.openSearchEnabled()) {
            return new ListingSearchRebuildResponse(
                    listingSearchProperties.engine(),
                    listingSearchProperties.opensearch().index(),
                    0);
        }
        List<ListingSearchDocument> documents = listingDraftRepository.findAllPublicListingsForSearchIndex().stream()
                .map(this::publicListingWithImagesAndNotice)
                .map(ListingSearchDocument::from)
                .toList();
        int indexed = openSearchListingSearchClient.rebuild(documents);
        log.info("Rebuilt listing search projection index={} indexedCount={}",
                openSearchListingSearchClient.indexName(), indexed);
        return new ListingSearchRebuildResponse(
                listingSearchProperties.engine(),
                openSearchListingSearchClient.indexName(),
                indexed);
    }

    @Transactional(readOnly = true)
    // Provides ADM-00 dashboard counts without exposing the full moderation queue.
    public AdminListingModerationSummaryResponse adminModerationSummary() {
        AuthServiceClient.PlatformAdminAuthorization admin = requireAdminPermission(AdminPermission.DASHBOARD_READ);
        return new AdminListingModerationSummaryResponse(
                listingDraftRepository.countPendingReview(),
                moderationCaseRepository.countAssignedListingReviewCases(admin.userId()));
    }

    @Transactional(readOnly = true)
    // Reads the case-backed listing moderation queue used by platform admins.
    public List<AdminListingModerationCaseResponse> getListingModerationCases(String filter, String query) {
        AdminActor admin = requireAdminActor(AdminPermission.LISTING_MODERATION_READ);
        return withAdminIdentityLabels(
                moderationCaseRepository.findListingReviewCases(
                        normalizedCaseFilter(filter),
                        admin.authorization().userId(),
                        normalizedOptionalSearchQuery(query)),
                admin.accessToken());
    }

    @Transactional(readOnly = true)
    // Reads all review context needed by the admin listing detail page.
    public AdminListingModerationCaseDetailResponse getListingModerationCaseDetail(String caseId) {
        AdminActor admin = requireAdminActor(AdminPermission.LISTING_MODERATION_READ);
        AdminListingModerationCaseResponse moderationCase = loadListingReviewCase(caseId);
        return buildListingModerationCaseDetail(enrichListingReviewCase(moderationCase, admin));
    }

    @Transactional(readOnly = true)
    // Returns case and listing actions in the normalized ADM-AUD-01 timeline contract.
    public List<AdminTimelineEntryResponse> getListingModerationTimeline(String caseId) {
        AdminActor admin = requireAdminActor(AdminPermission.LISTING_MODERATION_READ);
        requirePermission(admin.authorization(), AdminPermission.AUDIT_READ);
        AdminListingModerationCaseResponse moderationCase = loadListingReviewCase(caseId);
        List<AdminTimelineEntryResponse> caseEvents = new ArrayList<>(
                moderationCaseEventRepository.findTimeline(moderationCase.id()));
        boolean lifecycleStartPresent = caseEvents.stream().anyMatch(event ->
                "CASE_CREATED".equals(event.eventType()) || "CASE_REOPENED".equals(event.eventType()));
        if (!lifecycleStartPresent) {
            caseEvents.add(new AdminTimelineEntryResponse(
                    null,
                    moderationCase.createdAt(),
                    "CASE_CREATED",
                    "MARKETPLACE_USER",
                    moderationCase.submittedByUserId(),
                    moderationCase.submittedByUserId(),
                    "SYSTEM",
                    "LISTING",
                    moderationCase.listingId(),
                    moderationCase.id(),
                    null,
                    "OPEN",
                    null,
                    null,
                    Map.of("legacyDerived", "true")));
        }
        boolean claimPresent = caseEvents.stream().anyMatch(event -> "CASE_CLAIMED".equals(event.eventType()));
        if (!claimPresent && moderationCase.assignedAdminUserId() != null) {
            caseEvents.add(new AdminTimelineEntryResponse(
                    null,
                    moderationCase.updatedAt(),
                    "CASE_CLAIMED",
                    "PLATFORM_ADMIN",
                    moderationCase.assignedAdminUserId(),
                    moderationCase.assignedAdminUserId(),
                    "HUMAN_ADMIN",
                    "LISTING",
                    moderationCase.listingId(),
                    moderationCase.id(),
                    "OPEN",
                    moderationCase.caseStatus(),
                    null,
                    null,
                    Map.of("legacyDerived", "true",
                            "newAssignedAdminUserId", moderationCase.assignedAdminUserId())));
        }
        caseEvents.addAll(listingModerationDecisionRepository.findTimeline(
                moderationCase.listingId(), moderationCase.id()));
        return withAdminTimelineActorLabels(
                caseEvents.stream()
                        .sorted(Comparator.comparing(AdminTimelineEntryResponse::occurredAt)
                                .thenComparing(entry -> entry.eventId() == null ? "" : entry.eventId()))
                        .toList(),
                admin.accessToken());
    }

    @Transactional
    // Claims an open listing review case for the current admin using the case version as the lock.
    public AdminListingModerationCaseResponse claimListingModerationCase(String caseId, long expectedVersion) {
        AdminActor admin = requireAdminActor(AdminPermission.LISTING_MODERATION_CLAIM);
        String normalizedCaseId = normalizedRequiredId("Case ID", caseId);
        AdminListingModerationCaseResponse before = loadListingReviewCase(normalizedCaseId);
        Instant now = Instant.now();
        int updated = moderationCaseRepository.claimListingReviewCase(
                normalizedCaseId,
                expectedVersion,
                admin.authorization().userId(),
                now);
        if (updated == 0) {
            throw new ModerationCaseVersionConflictException();
        }
        recordModerationCaseEvent(new ModerationCaseEventInsert(
                ulidGenerator.next(), normalizedCaseId, before.listingId(), "CASE_CLAIMED",
                admin.authorization().userId(), before.caseStatus(), "CLAIMED",
                before.assignedAdminUserId(), admin.authorization().userId(), null,
                currentCorrelationId(), now));
        log.info("Admin claimed listing moderation case caseId={} adminUserId={}",
                normalizedCaseId, admin.authorization().userId());
        return enrichListingReviewCase(loadListingReviewCase(normalizedCaseId), admin);
    }

    @Transactional
    // Releases a claimed listing review case only when it is still assigned to the current admin.
    public AdminListingModerationCaseResponse releaseListingModerationCase(String caseId, long expectedVersion) {
        AdminActor admin = requireAdminActor(AdminPermission.LISTING_MODERATION_CLAIM);
        String normalizedCaseId = normalizedRequiredId("Case ID", caseId);
        AdminListingModerationCaseResponse before = loadListingReviewCase(normalizedCaseId);
        Instant now = Instant.now();
        int updated = moderationCaseRepository.releaseListingReviewCase(
                normalizedCaseId,
                expectedVersion,
                admin.authorization().userId(),
                now);
        if (updated == 0) {
            throw new ModerationCaseVersionConflictException();
        }
        recordModerationCaseEvent(new ModerationCaseEventInsert(
                ulidGenerator.next(), normalizedCaseId, before.listingId(), "CASE_RELEASED",
                admin.authorization().userId(), before.caseStatus(), "OPEN",
                before.assignedAdminUserId(), null, null, currentCorrelationId(), now));
        log.info("Admin released listing moderation case caseId={} adminUserId={}",
                normalizedCaseId, admin.authorization().userId());
        return enrichListingReviewCase(loadListingReviewCase(normalizedCaseId), admin);
    }

    @Transactional
    // Resolves a claimed review case and applies the listing moderation decision atomically.
    public AdminListingModerationCaseDetailResponse resolveListingModerationCase(
            String caseId,
            long expectedCaseVersion,
            ListingModerationDecisionRequest request) {
        AdminActor admin = requireAdminActor(AdminPermission.LISTING_MODERATION_RESOLVE);
        String normalizedCaseId = normalizedRequiredId("Case ID", caseId);
        AdminListingModerationCaseResponse moderationCase = loadListingReviewCase(normalizedCaseId);
        requireResolvableListingReviewCase(moderationCase, admin.authorization().userId(), expectedCaseVersion);

        ListingDraftResponse listing = listingDraftRepository.findOptionalById(moderationCase.listingId())
                .orElseThrow(ListingNotFoundException::new);
        Instant now = Instant.now();
        try {
            applyListingModerationDecision(
                    listing, listing.version(), request, admin.authorization().userId(), now, normalizedCaseId);
            int resolved = moderationCaseRepository.resolveListingReviewCase(
                    normalizedCaseId,
                    expectedCaseVersion,
                    admin.authorization().userId(),
                    now);
            if (resolved == 0) {
                throw new ModerationCaseVersionConflictException();
            }
            recordModerationCaseEvent(new ModerationCaseEventInsert(
                    ulidGenerator.next(), normalizedCaseId, listing.id(), "CASE_RESOLVED",
                    admin.authorization().userId(), moderationCase.caseStatus(), "RESOLVED",
                    moderationCase.assignedAdminUserId(), moderationCase.assignedAdminUserId(),
                    normalizedRequiredText("Reason", request.reason(), 1000), currentCorrelationId(), now));
            log.info("Admin resolved listing moderation case caseId={} listingId={} adminUserId={}",
                    normalizedCaseId, listing.id(), admin.authorization().userId());
            return buildListingModerationCaseDetail(
                    enrichListingReviewCase(loadListingReviewCase(normalizedCaseId), admin));
        } catch (RuntimeException exception) {
            log.error(
                    "Admin listing moderation resolution failed caseId={} listingId={} exceptionType={} rootCauseType={}",
                    normalizedCaseId,
                    listing.id(),
                    exception.getClass().getSimpleName(),
                    rootCauseType(exception));
            throw exception;
        }
    }

    @Transactional
    // Applies the platform moderation decision and records immutable reviewer history.
    public ListingModerationDecisionResponse decideListing(
            String listingId,
            long expectedVersion,
            ListingModerationDecisionRequest request) {
        AuthServiceClient.PlatformAdminAuthorization reviewer = requireAdminPermission(
                AdminPermission.LISTING_MODERATION_RESOLVE);
        String normalizedListingId = normalizedRequiredId("Listing ID", listingId);
        ListingDraftResponse listing = listingDraftRepository.findOptionalById(normalizedListingId)
                .orElseThrow(ListingNotFoundException::new);
        ListingModerationDecisionResponse response =
                applyListingModerationDecision(listing, expectedVersion, request, reviewer.userId(), Instant.now());
        log.info("Admin listing moderation decision listingId={} reviewerUserId={} decision={}",
                listing.id(), reviewer.userId(), response.decision());
        return response;
    }

    private AdminListingModerationCaseResponse loadListingReviewCase(String caseId) {
        return moderationCaseRepository.findListingReviewCaseById(normalizedRequiredId("Case ID", caseId))
                .orElseThrow(ModerationCaseNotFoundException::new);
    }

    private AdminListingModerationCaseResponse enrichListingReviewCase(
            AdminListingModerationCaseResponse moderationCase,
            AdminActor admin) {
        return withAdminIdentityLabels(moderationCase, admin.accessToken());
    }

    private void requireResolvableListingReviewCase(
            AdminListingModerationCaseResponse moderationCase,
            String adminUserId,
            long expectedCaseVersion) {
        if (!adminUserId.equals(moderationCase.assignedAdminUserId())
                || !"CLAIMED".equals(moderationCase.caseStatus())
                || moderationCase.version() != expectedCaseVersion) {
            throw new ModerationCaseVersionConflictException();
        }
    }

    private ListingModerationDecisionResponse applyListingModerationDecision(
            ListingDraftResponse listing,
            ListingModerationDecisionRequest request,
            String reviewerUserId,
            Instant now) {
        return applyListingModerationDecision(listing, listing.version(), request, reviewerUserId, now, null);
    }

    private ListingModerationDecisionResponse applyListingModerationDecision(
            ListingDraftResponse listing,
            long expectedVersion,
            ListingModerationDecisionRequest request,
            String reviewerUserId,
            Instant now) {
        return applyListingModerationDecision(listing, expectedVersion, request, reviewerUserId, now, null);
    }

    private ListingModerationDecisionResponse applyListingModerationDecision(
            ListingDraftResponse listing,
            long expectedVersion,
            ListingModerationDecisionRequest request,
            String reviewerUserId,
            Instant now,
            String moderationCaseId) {
        if (!"PENDING_REVIEW".equals(listing.status()) || !"PENDING".equals(listing.moderationStatus())) {
            log.warn("Rejected listing moderation decision listingId={} status={} moderationStatus={} reason=invalid_state",
                    listing.id(), listing.status(), listing.moderationStatus());
            throw new IllegalArgumentException("Only pending-review listings can receive a moderation decision.");
        }

        String decision = normalizedDecision(request.decision());
        if ("APPROVE".equals(decision) && "BUSINESS".equals(listing.sellerType())) {
            requireBusinessCapability(listing.businessId(), "BUSINESS_LISTING_PUBLICATION");
        }
        String reason = normalizedRequiredText("Reason", request.reason(), 1000);
        String nextStatus = switch (decision) {
            case "APPROVE" -> "ACTIVE";
            case "REJECT" -> "REJECTED";
            case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
            default -> throw new IllegalArgumentException("Decision must be APPROVE, REJECT, or REQUEST_CHANGES.");
        };
        String nextModerationStatus = switch (decision) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
            default -> throw new IllegalArgumentException("Decision must be APPROVE, REJECT, or REQUEST_CHANGES.");
        };

        Instant publishedAt = "APPROVE".equals(decision) ? now : null;
        String publicationSource = "APPROVE".equals(decision) ? "ADMIN_REVIEW" : null;
        int updated = listingDraftRepository.applyModerationDecision(
                listing.id(),
                expectedVersion,
                nextStatus,
                nextModerationStatus,
                publicationSource,
                publishedAt,
                now);
        if (updated == 0) {
            log.warn("Rejected listing moderation decision listingId={} expectedVersion={} reason=version_conflict",
                    listing.id(), expectedVersion);
            throw new ListingVersionConflictException();
        }
        listingMediaRepository.markImagesModerationStatus(listing.id(), nextModerationStatus, Timestamp.from(now));
        reconcileListingKnowledge(listing, now);

        ListingModerationDecisionResponse response = listingModerationDecisionRepository.insert(
                new ListingModerationDecisionInsert(
                        ulidGenerator.next(),
                        listing.id(),
                        moderationCaseId,
                        decision,
                        reason,
                        reviewerUserId,
                        expectedVersion + 1,
                        listing.status() + "/" + listing.moderationStatus(),
                        nextStatus + "/" + nextModerationStatus,
                        currentCorrelationId(),
                        now));
        recordListingSearchProjection(listing.id(), now);
        return response;
    }

    private ListingDraftResponse activeApprovedListing(String listingId) {
        ListingDraftResponse listing = listingDraftRepository.findOptionalById(listingId)
                .orElseThrow(ListingNotFoundException::new);
        if (!"ACTIVE".equals(listing.status()) || !"APPROVED".equals(listing.moderationStatus())) {
            throw new IllegalArgumentException("Only active approved listings can use this admin action.");
        }
        return listing;
    }

    private void recordAdminListingAction(
            String listingId,
            String moderationCaseId,
            String decision,
            String reason,
            String adminUserId,
            long listingVersion,
            String previousState,
            String newState,
            Instant now) {
        listingModerationDecisionRepository.insert(new ListingModerationDecisionInsert(
                ulidGenerator.next(),
                listingId,
                moderationCaseId,
                decision,
                normalizedRequiredText("Reason", reason, 1000),
                adminUserId,
                listingVersion,
                previousState,
                newState,
                currentCorrelationId(),
                now));
    }

    private void recordModerationCaseEvent(ModerationCaseEventInsert event) {
        moderationCaseEventRepository.insert(event);
    }

    private String currentCorrelationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    private static String rootCauseType(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    @Transactional
    public ListingMediaResponse requestMediaUpload(
            String listingId,
            ListingMediaUploadRequest request) {
        ListingOwnerSnapshot listing = draftListingForMedia(listingId);
        requireCurrentUserSellingCapability();
        String mediaId = ulidGenerator.next();
        String contentType = normalizedContentType(request.contentType());
        long sizeBytes = validSize(request.sizeBytes());
        String checksum = normalizedChecksum(request.checksumSha256());
        String fileName = normalizedOptionalText("File name", request.fileName(), 255);
        String objectKey = objectKey(listing.id(), mediaId, fileName, contentType);
        StorageUploadTarget uploadTarget = listingMediaStorage.createUploadTarget(objectKey, contentType, sizeBytes);

        ListingMediaResponse response = listingMediaRepository.insertMedia(new ListingMediaInsert(
                mediaId,
                listing.id(),
                listing.sellerType(),
                listing.individualSellerUserId(),
                listing.businessId(),
                uploadTarget.bucket(),
                objectKey,
                fileName,
                contentType,
                sizeBytes,
                checksum,
                Instant.now()));

        log.info("Created listing media upload slot listingId={} mediaId={}", listing.id(), response.id());
        return withAppUploadTarget(response, uploadTarget);
    }

    @Transactional
    // Business store media uses the same storage rules after proving the item belongs to the current active store.
    public ListingMediaResponse requestBusinessStoreItemMediaUpload(
            String businessId,
            String listingId,
            ListingMediaUploadRequest request) {
        ListingDraftResponse listing = requireBusinessStoreItemMediaAccess(businessId, listingId);
        return withBusinessStoreAppUploadTarget(
                requestMediaUpload(listing.id(), request),
                listing.businessId());
    }

    @Transactional
    // Stores seller-uploaded bytes through the service to avoid browser-to-storage CORS and credential exposure.
    public void uploadMediaContent(String listingId, String mediaId, String contentType, byte[] bytes) {
        draftListingForMedia(listingId);
        requireCurrentUserSellingCapability();
        ListingMediaResponse media = listingMediaRepository.findMediaById(listingId, normalizedRequiredId("Media ID", mediaId))
                .orElseThrow(ListingMediaNotFoundException::new);
        if (!"PENDING_UPLOAD".equals(media.uploadStatus())) {
            throw new IllegalArgumentException("Media upload is not pending.");
        }
        String normalizedContentType = normalizedContentType(contentType);
        if (!media.contentType().equalsIgnoreCase(normalizedContentType)) {
            throw new IllegalArgumentException("Uploaded object content type does not match upload request.");
        }
        if (bytes == null || bytes.length != media.sizeBytes()) {
            throw new IllegalArgumentException("Uploaded object size does not match upload request.");
        }

        listingMediaStorage.uploadObject(media.objectKey(), media.contentType(), bytes);
        log.info("Stored listing media bytes listingId={} mediaId={} sizeBytes={}",
                media.listingId(), media.id(), bytes.length);
    }

    @Transactional
    public void uploadBusinessStoreItemMediaContent(
            String businessId,
            String listingId,
            String mediaId,
            String contentType,
            byte[] bytes) {
        ListingDraftResponse listing = requireBusinessStoreItemMediaAccess(businessId, listingId);
        uploadMediaContent(listing.id(), mediaId, contentType, bytes);
    }

    @Transactional
    public ListingMediaResponse confirmMediaUpload(
            String listingId,
            String mediaId,
            ListingMediaConfirmRequest request) {
        draftListingForMedia(listingId);
        requireCurrentUserSellingCapability();
        ListingMediaResponse media = listingMediaRepository.findMediaById(listingId, mediaId)
                .orElseThrow(ListingMediaNotFoundException::new);
        if (!"PENDING_UPLOAD".equals(media.uploadStatus())) {
            throw new IllegalArgumentException("Media upload is not pending.");
        }

        long sizeBytes = validSize(request.sizeBytes());
        if (sizeBytes != media.sizeBytes()) {
            throw new IllegalArgumentException("Confirmed media size does not match upload request.");
        }

        String checksum = normalizedChecksum(request.checksumSha256());
        if (media.checksumSha256() != null && checksum != null && !media.checksumSha256().equalsIgnoreCase(checksum)) {
            throw new IllegalArgumentException("Confirmed checksum does not match upload request.");
        }
        verifyMediaUpload(media);

        ListingMediaResponse response = listingMediaRepository.confirmMedia(
                listingId,
                mediaId,
                sizeBytes,
                checksum == null ? media.checksumSha256() : checksum.toLowerCase(Locale.ROOT),
                Timestamp.from(Instant.now()));

        log.info("Confirmed listing media upload listingId={} mediaId={}", listingId, mediaId);
        return response;
    }

    @Transactional
    public ListingMediaResponse confirmBusinessStoreItemMediaUpload(
            String businessId,
            String listingId,
            String mediaId,
            ListingMediaConfirmRequest request) {
        ListingDraftResponse listing = requireBusinessStoreItemMediaAccess(businessId, listingId);
        return confirmMediaUpload(listing.id(), mediaId, request);
    }

    @Transactional(readOnly = true)
    public ListingMediaContent publicListingMediaContent(String imageId) {
        ListingMediaResponse media = listingMediaRepository.findPublicImageMediaByImageId(normalizedRequiredId("Image ID", imageId))
                .orElseThrow(ListingMediaNotFoundException::new);
        return mediaContent(media);
    }

    @Transactional(readOnly = true)
    public ListingMediaContent ownedListingMediaContent(String listingId, String mediaId) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        ListingMediaResponse media = listingMediaRepository.findMediaById(listing.id(), normalizedRequiredId("Media ID", mediaId))
                .orElseThrow(ListingMediaNotFoundException::new);
        if (!"UPLOADED".equals(media.uploadStatus())) {
            throw new ListingMediaNotFoundException();
        }
        return mediaContent(media);
    }

    @Transactional(readOnly = true)
    // Allows platform admins to inspect uploaded listing images during moderation without using seller ownership.
    public ListingMediaContent adminListingMediaContent(String listingId, String mediaId) {
        requireAdminPermission(AdminPermission.LISTING_MODERATION_READ);
        ListingMediaResponse media = listingMediaRepository.findMediaById(
                        normalizedRequiredId("Listing ID", listingId),
                        normalizedRequiredId("Media ID", mediaId))
                .orElseThrow(ListingMediaNotFoundException::new);
        if (!"UPLOADED".equals(media.uploadStatus())) {
            throw new ListingMediaNotFoundException();
        }
        return mediaContent(media);
    }

    private ListingMediaContent mediaContent(ListingMediaResponse media) {
        try {
            return new ListingMediaContent(media.contentType(), listingMediaStorage.readObject(media.objectKey()));
        } catch (StorageObjectAccessDeniedException exception) {
            log.warn("Listing media object access denied listingId={} mediaId={} objectKey={} reason={}",
                    media.listingId(), media.id(), media.objectKey(), exception.getMessage());
            throw new ListingMediaAccessDeniedException(exception.getMessage());
        } catch (StorageObjectNotFoundException exception) {
            log.warn("Listing media object missing listingId={} mediaId={} objectKey={}",
                    media.listingId(), media.id(), media.objectKey());
            throw new ListingMediaNotFoundException();
        }
    }

    @Transactional
    public List<ListingImageResponse> updateListingImages(
            String listingId,
            UpdateListingImagesRequest request) {
        ListingOwnerSnapshot listing = draftListingForMedia(listingId);
        requireCurrentUserSellingCapability();
        ListingDraftResponse before = listingDraftRepository.findOptionalById(listing.id())
                .orElseThrow(ListingNotFoundException::new);
        List<ListingImageRequest> requestedImages = request.images() == null ? List.of() : request.images();
        if (requestedImages.size() > 10) {
            throw new IllegalArgumentException("A listing can have up to 10 images.");
        }
        Set<String> seenMediaIds = new HashSet<>();
        List<ListingImageInsert> inserts = new ArrayList<>();
        Instant now = Instant.now();

        for (int index = 0; index < requestedImages.size(); index++) {
            ListingImageRequest image = requestedImages.get(index);
            String mediaId = normalizedRequiredId("Media ID", image.mediaId());
            if (!seenMediaIds.add(mediaId)) {
                throw new IllegalArgumentException("Listing images must not contain duplicate media.");
            }

            ListingMediaResponse media = listingMediaRepository.findMediaById(listing.id(), mediaId)
                    .orElseThrow(ListingMediaNotFoundException::new);
            if (!"UPLOADED".equals(media.uploadStatus())) {
                throw new IllegalArgumentException("Listing images must reference uploaded media.");
            }

            inserts.add(new ListingImageInsert(
                    ulidGenerator.next(),
                    listing.id(),
                    media.id(),
                    index,
                    normalizedOptionalText("Alt text", image.altText(), 250),
                    now));
        }

        listingMediaRepository.replaceImages(listing.id(), inserts);
        int movedOutOfPublicState = listingDraftRepository.markSellerEdited(listing.id(), now);
        reconcileListingKnowledge(before, now);
        if (movedOutOfPublicState > 0) {
            recordListingSearchProjection(listing.id(), now);
        }
        List<ListingImageResponse> images = listingMediaRepository.findImagesByListingId(listing.id());
        log.info("Updated listing images listingId={} count={}", listing.id(), images.size());
        return images;
    }

    @Transactional
    public List<ListingImageResponse> updateBusinessStoreItemImages(
            String businessId,
            String listingId,
            UpdateListingImagesRequest request) {
        ListingDraftResponse listing = requireBusinessStoreItemMediaAccess(businessId, listingId);
        return updateListingImages(listing.id(), request);
    }

    // Persists the exact aggregate version and visibility decision in the caller transaction.
    private void recordListingSearchProjection(String listingId, Instant occurredAt) {
        listingSearchProjectionIntentService.recordCurrentState(listingId, occurredAt);
    }

    private ListingDraftResponse createIndividualDraft(
            CreateListingDraftRequest request, ValidatedCatalogValues catalogValues) {
        if (request.businessId() != null && !request.businessId().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a business.");
        }
        if (request.sku() != null && !request.sku().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a SKU.");
        }

        CurrentActor actor = currentActorProvider.currentActor();
        IndividualSellerAuthorization seller = authServiceClient.requireActiveIndividualSeller(actor.accessToken());
        requireSellingCapability(seller.userId());
        ListingLocation location = individualLocation(request, seller);

        return listingDraftRepository.insertDraft(new ListingDraftInsert(
                ulidGenerator.next(),
                ListingSellerType.INDIVIDUAL,
                seller.userId(),
                null,
                null,
                request.categoryId(),
                normalizedText("Title", request.title(), 160),
                normalizedText("Description", request.description(), 5000),
                request.condition(),
                normalizedOptionalText("Condition notes", request.conditionNotes(), 1000),
                request.price().amount(),
                request.price().currency().toUpperCase(Locale.ROOT),
                Boolean.TRUE.equals(request.negotiable()),
                null,
                listingQuantity(request.quantity()),
                location.city(),
                location.region(),
                catalogValues.ruleVersion(),
                Instant.now()));
    }

    // Draft reads include attached image metadata so seller UIs can show saved media.
    private List<ListingDraftResponse> withImages(List<ListingDraftResponse> listings) {
        return listings.stream()
                .map(this::withImages)
                .toList();
    }

    private ListingDraftResponse withImages(ListingDraftResponse listing) {
        ListingDraftResponse withMedia = new ListingDraftResponse(
                listing.id(),
                listing.sellerType(),
                listing.individualSellerUserId(),
                listing.businessId(),
                listing.storeId(),
                listing.sellerDisplayName(),
                listing.categoryId(),
                listing.title(),
                listing.description(),
                listing.condition(),
                listing.conditionNotes(),
                listing.priceAmount(),
                listing.currency(),
                listing.negotiable(),
                listing.sku(),
                listing.quantity(),
                listing.publicCity(),
                listing.publicRegion(),
                listing.status(),
                listing.moderationStatus(),
                listing.publicationSource(),
                listing.publishedAt(),
                listing.version(),
                listing.createdAt(),
                listing.updatedAt(),
                listing.moderationAction(),
                listing.moderationReason(),
                listing.moderationActionAt(),
                listingMediaRepository.findImagesByListingId(listing.id()));
        return withMedia.withModerationAction(
                listingModerationDecisionRepository.findLatestByListingId(listing.id()).orElse(null));
    }

    private List<ListingDraftResponse> withSellerLabels(List<ListingDraftResponse> listings) {
        AuthServiceClient.AdminIdentityLabels labels = publicSellerLabels(
                collectSellerIds(
                        listings,
                        listing -> "INDIVIDUAL".equals(listing.sellerType()),
                        ListingDraftResponse::individualSellerUserId),
                collectSellerIds(
                        listings,
                        listing -> "BUSINESS".equals(listing.sellerType()),
                        ListingDraftResponse::businessId));
        Map<String, String> users = userLabelMap(labels);
        Map<String, String> businesses = businessLabelMap(labels);
        return listings.stream()
                .map(listing -> listing.withSellerDisplayName(sellerDisplayName(listing, users, businesses)))
                .toList();
    }

    private ListingDraftResponse withSellerLabels(ListingDraftResponse listing) {
        return withSellerLabels(List.of(listing)).get(0);
    }

    private List<PublicListingResponse> withPublicSellerLabels(List<PublicListingResponse> listings) {
        AuthServiceClient.AdminIdentityLabels labels = publicSellerLabels(
                collectSellerIds(
                        listings,
                        listing -> "INDIVIDUAL".equals(listing.sellerType()),
                        PublicListingResponse::sellerId),
                collectSellerIds(
                        listings,
                        listing -> "BUSINESS".equals(listing.sellerType()),
                        PublicListingResponse::sellerId));
        Map<String, String> users = userLabelMap(labels);
        Map<String, String> businesses = businessLabelMap(labels);
        Map<String, AuthServiceClient.BusinessIdentityLabel> businessIdentities = businessIdentityMap(labels);
        Map<String, String> userAvatars = userAvatarMap(labels);
        return listings.stream()
                .map(listing -> {
                    if ("BUSINESS".equals(listing.sellerType())) {
                        AuthServiceClient.BusinessIdentityLabel business = businessIdentities.get(listing.sellerId());
                        if (business != null) {
                            String storeName = hasText(business.storeName()) ? business.storeName() : business.legalName();
                            return listing.withBusinessStoreIdentity(
                                    storeName,
                                    business.storeId(),
                                    business.storeSlug(),
                                    storeName,
                                    business.verified(),
                                    business.publicCity(),
                                    business.publicRegion());
                        }
                    }
                    return listing.withSellerLabel(
                            sellerDisplayName(listing, users, businesses),
                            "INDIVIDUAL".equals(listing.sellerType()) ? userAvatars.get(listing.sellerId()) : null);
                })
                .toList();
    }

    // Collects stable, nonblank owner IDs before the auth-service label lookup.
    private <T> Set<String> collectSellerIds(
            List<T> listings,
            Predicate<T> sellerTypeFilter,
            Function<T, String> idExtractor) {
        return listings.stream()
                .filter(sellerTypeFilter)
                .map(idExtractor)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private AuthServiceClient.AdminIdentityLabels publicSellerLabels(Set<String> userIds, Set<String> businessIds) {
        Set<String> normalizedUserIds = userIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> normalizedBusinessIds = businessIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedUserIds.isEmpty() && normalizedBusinessIds.isEmpty()) {
            return new AuthServiceClient.AdminIdentityLabels(List.of(), List.of());
        }
        try {
            AuthServiceClient.AdminIdentityLabels labels =
                    authServiceClient.lookupPublicSellerLabels(normalizedUserIds, normalizedBusinessIds);
            return labels == null ? new AuthServiceClient.AdminIdentityLabels(List.of(), List.of()) : labels;
        } catch (ListingAuthorizationException exception) {
            log.warn("Auth-service public seller label lookup failed; using neutral seller labels.");
            return new AuthServiceClient.AdminIdentityLabels(List.of(), List.of());
        } catch (RuntimeException exception) {
            log.warn("Auth-service public seller label lookup unavailable; using neutral seller labels.");
            return new AuthServiceClient.AdminIdentityLabels(List.of(), List.of());
        }
    }

    private Map<String, String> userLabelMap(AuthServiceClient.AdminIdentityLabels labels) {
        return safeUsers(labels).stream()
                .filter(label -> label.displayName() != null)
                .collect(Collectors.toMap(
                        AuthServiceClient.UserIdentityLabel::id,
                        AuthServiceClient.UserIdentityLabel::displayName,
                        (left, right) -> left));
    }

    private Map<String, String> businessLabelMap(AuthServiceClient.AdminIdentityLabels labels) {
        return safeBusinesses(labels).stream()
                .filter(label -> label.legalName() != null)
                .collect(Collectors.toMap(
                        AuthServiceClient.BusinessIdentityLabel::id,
                        AuthServiceClient.BusinessIdentityLabel::legalName,
                        (left, right) -> left));
    }

    private Map<String, AuthServiceClient.BusinessIdentityLabel> businessIdentityMap(
            AuthServiceClient.AdminIdentityLabels labels) {
        return safeBusinesses(labels).stream()
                .collect(Collectors.toMap(
                        AuthServiceClient.BusinessIdentityLabel::id,
                        Function.identity(),
                        (left, right) -> left));
    }

    private Map<String, String> userAvatarMap(AuthServiceClient.AdminIdentityLabels labels) {
        return safeUsers(labels).stream()
                .filter(label -> label.avatarUrl() != null)
                .collect(Collectors.toMap(
                        AuthServiceClient.UserIdentityLabel::id,
                        AuthServiceClient.UserIdentityLabel::avatarUrl,
                        (left, right) -> left));
    }

    private List<AuthServiceClient.UserIdentityLabel> safeUsers(AuthServiceClient.AdminIdentityLabels labels) {
        return labels == null || labels.users() == null ? List.of() : labels.users();
    }

    private List<AuthServiceClient.BusinessIdentityLabel> safeBusinesses(AuthServiceClient.AdminIdentityLabels labels) {
        return labels == null || labels.businesses() == null ? List.of() : labels.businesses();
    }

    private String sellerDisplayName(
            ListingDraftResponse listing,
            Map<String, String> users,
            Map<String, String> businesses) {
        return "BUSINESS".equals(listing.sellerType())
                ? businesses.getOrDefault(listing.businessId(), DEFAULT_BUSINESS_SELLER_LABEL)
                : users.getOrDefault(listing.individualSellerUserId(), DEFAULT_INDIVIDUAL_SELLER_LABEL);
    }

    private String sellerDisplayName(
            PublicListingResponse listing,
            Map<String, String> users,
            Map<String, String> businesses) {
        return "BUSINESS".equals(listing.sellerType())
                ? businesses.getOrDefault(listing.sellerId(), DEFAULT_BUSINESS_SELLER_LABEL)
                : users.getOrDefault(listing.sellerId(), DEFAULT_INDIVIDUAL_SELLER_LABEL);
    }

    private AdminListingModerationCaseDetailResponse buildListingModerationCaseDetail(
            AdminListingModerationCaseResponse moderationCase) {
        ListingDraftResponse listing = withSellerLabels(withImages(listingDraftRepository.findOptionalById(moderationCase.listingId())
                .orElseThrow(ListingNotFoundException::new)));
        return new AdminListingModerationCaseDetailResponse(
                moderationCase,
                listing,
                listingModerationDecisionRepository.findByListingId(listing.id()));
    }

    private ListingDraftResponse createBusinessDraft(
            CreateListingDraftRequest request, ValidatedCatalogValues catalogValues) {
        String businessId = normalizedRequiredId("Business ID", request.businessId());
        if (Boolean.TRUE.equals(request.negotiable())) {
            throw new IllegalArgumentException("Business listings are not negotiable.");
        }
        if (request.quantity() == null) {
            throw new IllegalArgumentException("Business listing quantity is required.");
        }

        CurrentActor actor = currentActorProvider.currentActor();
        AuthServiceClient.BusinessStoreContextAuthorization context =
                authServiceClient.requireBusinessStoreContext(actor.accessToken(), businessId);
        requireCurrentUserSellingCapability();
        requireBusinessCapability(context.businessId(), "BUSINESS_LISTING_CREATION");

        String sku = normalizedRequiredText("SKU", request.sku(), 64);
        ListingDraftInsert draft = new ListingDraftInsert(
                ulidGenerator.next(),
                ListingSellerType.BUSINESS,
                null,
                context.businessId(),
                context.store().id(),
                request.categoryId(),
                normalizedText("Title", request.title(), 160),
                normalizedText("Description", request.description(), 5000),
                request.condition(),
                normalizedOptionalText("Condition notes", request.conditionNotes(), 1000),
                request.price().amount(),
                request.price().currency().toUpperCase(Locale.ROOT),
                false,
                sku,
                request.quantity(),
                null,
                null,
                catalogValues.ruleVersion(),
                Instant.now());
        try {
            return listingDraftRepository.insertDraft(draft);
        } catch (DuplicateKeyException exception) {
            throw duplicateBusinessSku(context.businessId(), sku);
        }
    }

    // Reads the new aggregate version and records Product-owned AI source intents inside the caller transaction.
    private ListingDraftResponse reconcileListingKnowledge(ListingDraftResponse before, Instant occurredAt) {
        ListingDraftResponse after = listingDraftRepository.findOptionalById(before.id())
                .orElseThrow(ListingNotFoundException::new);
        listingKnowledgePublicationService.reconcile(before, after, occurredAt);
        listingDiscoveryEmbeddingRequestService.createForCurrentEligibleVersion(after, occurredAt);
        return after;
    }

    private BusinessSkuConflictException duplicateBusinessSku(String businessId, String sku) {
        log.warn("Rejected duplicate business SKU businessId={} sku={}", businessId, sku);
        return new BusinessSkuConflictException();
    }

    private ListingDraftUpdate individualUpdate(ListingOwnerSnapshot listing, CreateListingDraftRequest request) {
        if (request.businessId() != null && !request.businessId().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a business.");
        }
        if (request.sku() != null && !request.sku().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a SKU.");
        }

        ListingLocation location = individualLocation(request, new IndividualSellerAuthorization(
                listing.individualSellerUserId(),
                null,
                null,
                "ACTIVE"));
        return new ListingDraftUpdate(
                request.categoryId(),
                normalizedText("Title", request.title(), 160),
                normalizedText("Description", request.description(), 5000),
                request.condition(),
                normalizedOptionalText("Condition notes", request.conditionNotes(), 1000),
                request.price().amount(),
                request.price().currency().toUpperCase(Locale.ROOT),
                Boolean.TRUE.equals(request.negotiable()),
                null,
                listingQuantity(request.quantity()),
                location.city(),
                location.region());
    }

    private ListingDraftUpdate businessUpdate(ListingOwnerSnapshot listing, CreateListingDraftRequest request) {
        String businessId = normalizedRequiredId("Business ID", request.businessId());
        if (!listing.businessId().equals(businessId)) {
            throw new IllegalArgumentException("Listing business cannot be changed.");
        }
        if (Boolean.TRUE.equals(request.negotiable())) {
            throw new IllegalArgumentException("Business listings are not negotiable.");
        }
        if (request.quantity() == null) {
            throw new IllegalArgumentException("Business listing quantity is required.");
        }

        return new ListingDraftUpdate(
                request.categoryId(),
                normalizedText("Title", request.title(), 160),
                normalizedText("Description", request.description(), 5000),
                request.condition(),
                normalizedOptionalText("Condition notes", request.conditionNotes(), 1000),
                request.price().amount(),
                request.price().currency().toUpperCase(Locale.ROOT),
                false,
                normalizedRequiredText("SKU", request.sku(), 64),
                request.quantity(),
                null,
                null);
    }

    private ListingDraftUpdate adminActiveListingUpdate(
            ListingDraftResponse listing,
            AdminActiveListingUpdateRequest request) {
        if ("INDIVIDUAL".equals(listing.sellerType())) {
            return adminIndividualActiveListingUpdate(request);
        }
        return adminBusinessActiveListingUpdate(request);
    }

    private ListingDraftUpdate adminIndividualActiveListingUpdate(AdminActiveListingUpdateRequest request) {
        if (request.quantity() != null && request.quantity() != 1) {
            throw new IllegalArgumentException("Individual listing quantity must be 1.");
        }
        if (request.sku() != null && !request.sku().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a SKU.");
        }
        if (request.location() == null) {
            throw new IllegalArgumentException("Individual listing location is required.");
        }
        String city = normalizedRequiredText("Public city", request.location().city(), 120);
        String region = normalizedRequiredText("Public region", request.location().region(), 120);

        return new ListingDraftUpdate(
                request.categoryId(),
                normalizedText("Title", request.title(), 160),
                normalizedText("Description", request.description(), 5000),
                request.condition(),
                normalizedOptionalText("Condition notes", request.conditionNotes(), 1000),
                request.price().amount(),
                request.price().currency().toUpperCase(Locale.ROOT),
                Boolean.TRUE.equals(request.negotiable()),
                null,
                1,
                city,
                region);
    }

    private ListingDraftUpdate adminBusinessActiveListingUpdate(AdminActiveListingUpdateRequest request) {
        if (Boolean.TRUE.equals(request.negotiable())) {
            throw new IllegalArgumentException("Business listings are not negotiable.");
        }
        if (request.quantity() == null) {
            throw new IllegalArgumentException("Business listing quantity is required.");
        }

        return new ListingDraftUpdate(
                request.categoryId(),
                normalizedText("Title", request.title(), 160),
                normalizedText("Description", request.description(), 5000),
                request.condition(),
                normalizedOptionalText("Condition notes", request.conditionNotes(), 1000),
                request.price().amount(),
                request.price().currency().toUpperCase(Locale.ROOT),
                false,
                normalizedRequiredText("SKU", request.sku(), 64),
                request.quantity(),
                null,
                null);
    }

    private ListingLocation individualLocation(
            CreateListingDraftRequest request,
            IndividualSellerAuthorization seller) {
        if (request.location() == null) {
            return new ListingLocation(seller.publicCity(), seller.publicRegion());
        }

        String city = normalizedOptionalText("Public city", request.location().city(), 120);
        String region = normalizedOptionalText("Public region", request.location().region(), 120);
        return new ListingLocation(
                city == null ? seller.publicCity() : city,
                region == null ? seller.publicRegion() : region);
    }

    private ListingOwnerSnapshot draftListingForMedia(String listingId) {
        ListingOwnerSnapshot listing = ownedListing(listingId);
        if (listing.sellerType() == ListingSellerType.BUSINESS && !canBusinessSellerEdit(listing.status())) {
            throw new IllegalArgumentException("Pause an active store item before changing its images.");
        }
        if (!canSellerChangeMedia(listing.status())) {
            throw new IllegalArgumentException(
                    "Listing media can be changed only while the listing is draft, pending review, active, paused, or closed.");
        }
        return listing;
    }

    private ListingDraftResponse activePublicListing(String listingId) {
        ListingDraftResponse listing = listingDraftRepository.findOptionalById(listingId)
                .orElseThrow(ListingNotFoundException::new);
        boolean individualApproved = "INDIVIDUAL".equals(listing.sellerType())
                && "APPROVED".equals(listing.moderationStatus());
        boolean businessSelfPublished = "BUSINESS".equals(listing.sellerType())
                && "BUSINESS_SELF_PUBLISHED".equals(listing.publicationSource());
        if (!"ACTIVE".equals(listing.status()) || (!individualApproved && !businessSelfPublished)) {
            throw new IllegalArgumentException("Only active public listings can use this admin action.");
        }
        return listing;
    }

    private ListingDraftResponse requireBusinessStoreItemMediaAccess(String businessId, String listingId) {
        ListingDraftResponse listing = requireBusinessStoreItemAccess(businessId, listingId, true);
        if (!canBusinessSellerEdit(listing.status())) {
            throw new IllegalArgumentException("Pause an active store item before changing its images.");
        }
        return listing;
    }

    private ListingDraftResponse requireBusinessStoreItemAccess(String businessId, String listingId, boolean allowPaused) {
        String normalizedBusinessId = normalizedRequiredId("Business ID", businessId);
        String normalizedListingId = normalizedRequiredId("Listing ID", listingId);
        CurrentActor actor = currentActorProvider.currentActor();
        AuthServiceClient.BusinessStoreContextAuthorization context =
                authServiceClient.requireBusinessStoreContext(actor.accessToken(), normalizedBusinessId);
        ListingDraftResponse listing = listingDraftRepository.findOptionalById(normalizedListingId)
                .orElseThrow(ListingNotFoundException::new);
        if (!"BUSINESS".equals(listing.sellerType())
                || !context.businessId().equals(listing.businessId())
                || listing.storeId() == null
                || context.store() == null
                || !context.store().id().equals(listing.storeId())) {
            log.warn("Denied business store item media access listingId={} requestedBusinessId={} listingBusinessId={}",
                    normalizedListingId, normalizedBusinessId, listing.businessId());
            throw new ListingAuthorizationException("Listing belongs to another business store.");
        }
        if (!allowPaused && "PAUSED".equals(listing.status())) {
            throw new IllegalArgumentException("Paused store items must be relisted before editing media.");
        }
        return listing;
    }

    private void requirePublishableBusinessStoreItem(ListingDraftResponse listing) {
        if (!"BUSINESS".equals(listing.sellerType())) {
            throw new IllegalArgumentException("Only business store items can be published.");
        }
        if (!categoryRepository.activeCategoryExists(listing.categoryId())) {
            throw new CategoryNotFoundException();
        }
        if (listing.title() == null || listing.title().isBlank()) {
            throw new IllegalArgumentException("Title is required before publishing.");
        }
        if (listing.description() == null || listing.description().isBlank()) {
            throw new IllegalArgumentException("Description is required before publishing.");
        }
        if (listing.sku() == null || listing.sku().isBlank()) {
            throw new IllegalArgumentException("SKU is required before publishing.");
        }
        if (listing.quantity() < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1 before publishing.");
        }
        if (!listingMediaRepository.hasAttachedUploadedImage(listing.id())) {
            throw new IllegalArgumentException("At least one attached image is required before publishing.");
        }
    }

    // A requested-changes decision reopens the owner workflow without exposing the listing publicly.
    private boolean canSellerEdit(String status) {
        return "DRAFT".equals(status)
                || "PENDING_REVIEW".equals(status)
                || "ACTIVE".equals(status)
                || "CLOSED".equals(status)
                || "CHANGES_REQUESTED".equals(status);
    }

    private boolean canBusinessSellerEdit(String status) {
        return "DRAFT".equals(status) || "PAUSED".equals(status);
    }

    private boolean canSellerChangeMedia(String status) {
        return canSellerEdit(status) || "PAUSED".equals(status);
    }

    private boolean canSellerClose(String status) {
        return "DRAFT".equals(status) || "PENDING_REVIEW".equals(status) || "ACTIVE".equals(status);
    }

    private int listingQuantity(Integer quantity) {
        return quantity == null ? 1 : quantity;
    }

    private ListingOwnerSnapshot ownedListing(String listingId) {
        ListingOwnerSnapshot listing = listingDraftRepository.findOwnerSnapshot(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new);
        requireListingOwner(listing);
        return listing;
    }

    private OwnedListingSubmission ownedListingSubmission(String listingId) {
        ListingOwnerSnapshot listing = listingDraftRepository.findOwnerSnapshot(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new);
        return new OwnedListingSubmission(listing, requireListingOwner(listing));
    }

    private AuthServiceClient.PlatformAdminAuthorization requirePlatformAdmin() {
        CurrentActor actor = currentActorProvider.currentActor();
        return authServiceClient.requirePlatformAdmin(actor.accessToken());
    }

    private AuthServiceClient.PlatformAdminAuthorization requireAdminPermission(AdminPermission permission) {
        return requirePermission(requirePlatformAdmin(), permission);
    }

    private AdminActor requireAdminActor(AdminPermission permission) {
        CurrentActor actor = currentActorProvider.currentActor();
        AuthServiceClient.PlatformAdminAuthorization authorization =
                authServiceClient.requirePlatformAdmin(actor.accessToken());
        requirePermission(authorization, permission);
        return new AdminActor(actor.accessToken(), authorization);
    }

    private AuthServiceClient.PlatformAdminAuthorization requirePermission(
            AuthServiceClient.PlatformAdminAuthorization authorization,
            AdminPermission permission) {
        if (!authorization.hasPermission(permission)) {
            log.warn("Denied admin listing action adminUserId={} permission={}",
                    authorization.userId(), permission.id());
            throw new ListingAuthorizationException("Admin permission is required for this action.");
        }
        return authorization;
    }

    private List<AdminListingModerationCaseResponse> withAdminIdentityLabels(
            List<AdminListingModerationCaseResponse> cases,
            String accessToken) {
        if (cases.isEmpty()) {
            return cases;
        }

        LinkedHashSet<String> userIds = new LinkedHashSet<>();
        LinkedHashSet<String> businessIds = new LinkedHashSet<>();
        for (AdminListingModerationCaseResponse moderationCase : cases) {
            addIdentityId(moderationCase.assignedAdminUserId(), userIds);
            if ("BUSINESS".equals(moderationCase.sellerType())) {
                addIdentityId(moderationCase.sellerId(), businessIds);
            } else {
                addIdentityId(moderationCase.sellerId(), userIds);
            }
        }

        AuthServiceClient.AdminIdentityLabels labels =
                authServiceClient.lookupAdminIdentityLabels(accessToken, userIds, businessIds);
        if (labels == null) {
            labels = new AuthServiceClient.AdminIdentityLabels(List.of(), List.of());
        }
        List<AuthServiceClient.UserIdentityLabel> userLabels = labels.users() == null ? List.of() : labels.users();
        List<AuthServiceClient.BusinessIdentityLabel> businessLabels =
                labels.businesses() == null ? List.of() : labels.businesses();
        Map<String, AuthServiceClient.UserIdentityLabel> users = userLabels.stream()
                .collect(Collectors.toMap(
                        AuthServiceClient.UserIdentityLabel::id,
                        Function.identity(),
                        (first, ignored) -> first));
        Map<String, AuthServiceClient.BusinessIdentityLabel> businesses = businessLabels.stream()
                .collect(Collectors.toMap(
                        AuthServiceClient.BusinessIdentityLabel::id,
                        Function.identity(),
                        (first, ignored) -> first));

        return cases.stream()
                .map(moderationCase -> moderationCase.withIdentityLabels(
                        sellerDisplayName(moderationCase, users, businesses),
                        adminDisplayName(moderationCase, users)))
                .toList();
    }

    private AdminListingModerationCaseResponse withAdminIdentityLabels(
            AdminListingModerationCaseResponse moderationCase,
            String accessToken) {
        return withAdminIdentityLabels(List.of(moderationCase), accessToken).getFirst();
    }

    private List<AdminTimelineEntryResponse> withAdminTimelineActorLabels(
            List<AdminTimelineEntryResponse> timeline,
            String accessToken) {
        LinkedHashSet<String> userIds = timeline.stream()
                .map(AdminTimelineEntryResponse::actorId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (userIds.isEmpty()) {
            return timeline;
        }
        AuthServiceClient.AdminIdentityLabels labels =
                authServiceClient.lookupAdminIdentityLabels(accessToken, userIds, Set.of());
        Map<String, String> displayByUserId = labels == null || labels.users() == null
                ? Map.of()
                : labels.users().stream().collect(Collectors.toMap(
                        AuthServiceClient.UserIdentityLabel::id,
                        AuthServiceClient.UserIdentityLabel::displayName,
                        (first, ignored) -> first));
        return timeline.stream()
                .map(entry -> entry.actorId() == null
                        ? entry.withActorDisplay(entry.actorDisplay() == null ? "System" : entry.actorDisplay())
                        : entry.withActorDisplay(displayByUserId.getOrDefault(entry.actorId(), entry.actorId())))
                .toList();
    }

    private String sellerDisplayName(
            AdminListingModerationCaseResponse moderationCase,
            Map<String, AuthServiceClient.UserIdentityLabel> users,
            Map<String, AuthServiceClient.BusinessIdentityLabel> businesses) {
        if ("BUSINESS".equals(moderationCase.sellerType())) {
            AuthServiceClient.BusinessIdentityLabel business = businesses.get(moderationCase.sellerId());
            return business == null ? null : business.legalName();
        }
        AuthServiceClient.UserIdentityLabel user = users.get(moderationCase.sellerId());
        return user == null ? null : user.displayName();
    }

    private String adminDisplayName(
            AdminListingModerationCaseResponse moderationCase,
            Map<String, AuthServiceClient.UserIdentityLabel> users) {
        AuthServiceClient.UserIdentityLabel user = users.get(moderationCase.assignedAdminUserId());
        return user == null ? null : user.displayName();
    }

    private void addIdentityId(String id, Set<String> ids) {
        if (id != null && !id.isBlank()) {
            ids.add(id);
        }
    }

    private String normalizedCaseFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return "open";
        }
        String normalized = filter.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "open", "unassigned", "assigned_to_me", "resolved" -> normalized;
            default -> throw new IllegalArgumentException("Case filter must be open, unassigned, assigned_to_me, or resolved.");
        };
    }

    private String normalizedOptionalSearchQuery(String query) {
        String normalized = TextInputs.collapseWhitespaceToNull(query);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("Search query is too long.");
        }
        return normalized;
    }

    private String normalizedDecision(String decision) {
        String normalized = normalizedRequiredText("Decision", decision, 32).toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalized)
                && !"REJECT".equals(normalized)
                && !"REQUEST_CHANGES".equals(normalized)) {
            throw new IllegalArgumentException("Decision must be APPROVE, REJECT, or REQUEST_CHANGES.");
        }
        return normalized;
    }

    private String transactionNotice(String sellerType) {
        if ("INDIVIDUAL".equals(sellerType)) {
            return "Payment and delivery are arranged directly by participants. The platform does not verify or protect off-platform payment.";
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private CreateListingDraftRequest businessStoreItemRequest(String businessId, CreateListingDraftRequest request) {
        return new CreateListingDraftRequest(
                ListingSellerType.BUSINESS,
                businessId,
                request.categoryId(),
                request.title(),
                request.description(),
                request.condition(),
                request.conditionNotes(),
                request.price(),
                false,
                null,
                request.sku(),
                request.quantity(),
                request.categoryRuleVersion(),
                request.attributes());
    }

    private PublicListingResponse publicListingForEngagement(String listingId) {
        return listingDraftRepository.findPublicListingById(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new);
    }

    private String currentUserId() {
        CurrentActor actor = currentActorProvider.currentActor();
        return authServiceClient.requireCurrentUser(actor.accessToken()).id();
    }

    private String requireCurrentUserSellingCapability() {
        String userId = currentUserId();
        requireSellingCapability(userId);
        return userId;
    }

    // Enforces the acting user's Auth-owned selling capability before any listing mutation.
    private void requireSellingCapability(String userId) {
        authServiceClient.requireUserCapability(userId, "USER_SELLING");
    }

    // Business enforcement composes with membership and user enforcement; it never substitutes for either check.
    private void requireBusinessCapability(String businessId, String scope) {
        authServiceClient.requireBusinessCapability(businessId, scope);
    }

    private String requireListingOwner(ListingOwnerSnapshot listing) {
        CurrentActor actor = currentActorProvider.currentActor();
        if (listing.sellerType() == ListingSellerType.INDIVIDUAL) {
            IndividualSellerAuthorization seller = authServiceClient.requireActiveIndividualSeller(actor.accessToken());
            if (!listing.individualSellerUserId().equals(seller.userId())) {
                log.warn("Denied listing owner check listingId={} sellerType={} expectedUserId={} actualUserId={}",
                        listing.id(), listing.sellerType(), listing.individualSellerUserId(), seller.userId());
                throw new ListingAuthorizationException("Listing belongs to another seller.");
            }
            return seller.userId();
        }

        BusinessMembershipAuthorization membership =
                authServiceClient.requireBusinessListingPermission(actor.accessToken(), listing.businessId());
        if (!listing.businessId().equals(membership.businessId())) {
            log.warn("Denied listing owner check listingId={} sellerType={} expectedBusinessId={} actualBusinessId={}",
                    listing.id(), listing.sellerType(), listing.businessId(), membership.businessId());
            throw new ListingAuthorizationException("Listing belongs to another business.");
        }
        return membership.userId();
    }

    private record OwnedListingSubmission(ListingOwnerSnapshot listing, String submittedByUserId) {
    }

    // Adds listing/media context around storage verification failures without logging upload URLs or tokens.
    private void verifyMediaUpload(ListingMediaResponse media) {
        try {
            listingMediaStorage.verifyUploaded(media.objectKey(), media.contentType(), media.sizeBytes());
        } catch (IllegalArgumentException exception) {
            log.warn("Listing media verification failed listingId={} mediaId={} objectKey={} reason={}",
                    media.listingId(), media.id(), media.objectKey(), exception.getMessage());
            throw exception;
        }
    }

    private String normalizedContentType(String value) {
        String normalized = normalizedRequiredText("Content type", value, 80).toLowerCase(Locale.ROOT);
        int parameterStart = normalized.indexOf(';');
        if (parameterStart >= 0) {
            normalized = normalized.substring(0, parameterStart).trim();
        }
        if (!ALLOWED_IMAGE_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Only JPEG, PNG, and WebP images are supported.");
        }
        return normalized;
    }

    private long validSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Media size is required.");
        }
        if (sizeBytes > mediaStorageProperties.maxImageSizeBytes()) {
            throw new IllegalArgumentException("Media size must be 10 MB or less.");
        }
        return sizeBytes;
    }

    private ListingMediaResponse withUploadTarget(ListingMediaResponse response, StorageUploadTarget uploadTarget) {
        return new ListingMediaResponse(
                response.id(),
                response.listingId(),
                response.sellerType(),
                response.individualSellerUserId(),
                response.businessId(),
                response.objectBucket(),
                response.objectKey(),
                response.originalFileName(),
                response.contentType(),
                response.sizeBytes(),
                response.checksumSha256(),
                response.uploadStatus(),
                response.moderationStatus(),
                uploadTarget.uploadMethod(),
                uploadTarget.uploadUrl(),
                response.version(),
                response.createdAt(),
                response.updatedAt());
    }

    private ListingMediaResponse withAppUploadTarget(ListingMediaResponse response, StorageUploadTarget uploadTarget) {
        return withUploadTarget(response, new StorageUploadTarget(
                uploadTarget.bucket(),
                uploadTarget.objectKey(),
                "PUT",
                "/api/v1/listings/" + response.listingId() + "/media/" + response.id() + "/content"));
    }

    private ListingMediaResponse withBusinessStoreAppUploadTarget(ListingMediaResponse response, String businessId) {
        return withUploadTarget(response, new StorageUploadTarget(
                response.objectBucket(),
                response.objectKey(),
                "PUT",
                "/api/v1/businesses/" + businessId + "/store/items/" + response.listingId()
                        + "/media/" + response.id() + "/content"));
    }

    private String normalizedChecksum(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Checksum must be a SHA-256 hex value.");
        }
        return normalized;
    }

    private String objectKey(String listingId, String mediaId, String fileName, String contentType) {
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
        String baseName = fileName == null ? "image" + extension : SAFE_FILE_PART.matcher(fileName).replaceAll("-");
        if (baseName.length() > 80) {
            baseName = baseName.substring(baseName.length() - 80);
        }
        return "listings/" + listingId + "/" + mediaId + "/" + baseName;
    }

    private String normalizedRequiredId(String fieldName, String value) {
        return FixedLengthIds.requireTrimmed(fieldName, value, 26);
    }

    private String normalizedRequiredText(String fieldName, String value, int maxLength) {
        String normalized = normalizedText(fieldName, value, maxLength);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return normalized;
    }

    private String normalizedText(String fieldName, String value, int maxLength) {
        String normalized = TextInputs.collapseWhitespaceToEmpty(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long.");
        }
        return normalized;
    }

    private String normalizedOptionalText(String fieldName, String value, int maxLength) {
        String normalized = TextInputs.collapseWhitespaceToNull(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long.");
        }
        return normalized;
    }

    private record ListingLocation(String city, String region) {
    }

    private record AdminActor(
            String accessToken,
            AuthServiceClient.PlatformAdminAuthorization authorization
    ) {
    }
}
