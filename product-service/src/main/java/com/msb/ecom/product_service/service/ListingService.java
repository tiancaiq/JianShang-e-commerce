package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.core.validation.TextInputs;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.model.CategoryNotFoundException;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.model.ListingMediaNotFoundException;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import com.msb.ecom.product_service.model.ListingSellerType;
import com.msb.ecom.product_service.model.ListingVersionConflictException;
import com.msb.ecom.product_service.model.ModerationCaseNotFoundException;
import com.msb.ecom.product_service.model.ModerationCaseVersionConflictException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.AuthServiceClient.BusinessMembershipAuthorization;
import com.msb.ecom.product_service.service.AuthServiceClient.IndividualSellerAuthorization;
import com.msb.ecom.product_service.repository.CategoryRepository;
import com.msb.ecom.product_service.repository.ListingDraftInsert;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingDraftRepository.ListingOwnerSnapshot;
import com.msb.ecom.product_service.repository.ListingDraftUpdate;
import com.msb.ecom.product_service.repository.ListingImageInsert;
import com.msb.ecom.product_service.repository.ListingMediaInsert;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import com.msb.ecom.product_service.repository.ListingModerationDecisionInsert;
import com.msb.ecom.product_service.repository.ListingModerationDecisionRepository;
import com.msb.ecom.product_service.repository.ModerationCaseInsert;
import com.msb.ecom.product_service.repository.ModerationCaseRepository;
import com.msb.ecom.product_service.repository.ModerationCaseRepository.ModerationCaseEnsureResult;
import com.msb.ecom.product_service.service.UlidGenerator;
import com.msb.ecom.product_service.dto.AdminActiveListingUpdateRequest;
import com.msb.ecom.product_service.dto.AdminListingModerationCaseDetailResponse;
import com.msb.ecom.product_service.dto.AdminListingModerationCaseResponse;
import com.msb.ecom.product_service.dto.AdminListingModerationSummaryResponse;
import com.msb.ecom.product_service.dto.AdminListingRemoveRequest;
import com.msb.ecom.product_service.dto.CategoryResponse;
import com.msb.ecom.product_service.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.ListingImageRequest;
import com.msb.ecom.product_service.dto.ListingImageResponse;
import com.msb.ecom.product_service.dto.ListingMediaConfirmRequest;
import com.msb.ecom.product_service.dto.ListingMediaResponse;
import com.msb.ecom.product_service.dto.ListingMediaUploadRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionRequest;
import com.msb.ecom.product_service.dto.ListingModerationDecisionResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.UpdateListingImagesRequest;
import com.msb.ecom.product_service.storage.ListingMediaStorage;
import com.msb.ecom.product_service.storage.ListingMediaStorageProperties;
import com.msb.ecom.product_service.storage.StorageUploadTarget;
import com.msb.ecom.product_service.storage.StorageObjectNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListingService {

    static final int PUBLIC_BROWSE_LIMIT = 24;
    private static final Pattern SAFE_FILE_PART = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp");

    private final CategoryRepository categoryRepository;
    private final ListingDraftRepository listingDraftRepository;
    private final ListingMediaRepository listingMediaRepository;
    private final ListingModerationDecisionRepository listingModerationDecisionRepository;
    private final ModerationCaseRepository moderationCaseRepository;
    private final AuthServiceClient authServiceClient;
    private final UlidGenerator ulidGenerator;
    private final CurrentActorProvider currentActorProvider;
    private final ListingMediaStorage listingMediaStorage;
    private final ListingMediaStorageProperties mediaStorageProperties;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getActiveCategories() {
        return categoryRepository.findActiveCategories();
    }

    @Transactional
    public ListingDraftResponse createDraft(CreateListingDraftRequest request) {
        if (!categoryRepository.activeCategoryExists(request.categoryId())) {
            throw new CategoryNotFoundException();
        }

        ListingDraftResponse response = switch (request.sellerType()) {
            case INDIVIDUAL -> createIndividualDraft(request);
            case BUSINESS -> createBusinessDraft(request);
        };

        log.info("Created listing draft id={} sellerType={}", response.id(), response.sellerType());
        return response;
    }

    @Transactional(readOnly = true)
    public ListingDraftResponse getOwnedListing(String listingId) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        return withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<ListingDraftResponse> getCurrentIndividualSellerListings() {
        CurrentActor actor = currentActorProvider.currentActor();
        IndividualSellerAuthorization seller = authServiceClient.requireActiveIndividualSeller(actor.accessToken());
        return withImages(listingDraftRepository.findByIndividualSellerUserId(seller.userId()));
    }

    @Transactional(readOnly = true)
    public List<ListingDraftResponse> getBusinessListings(String businessId) {
        String normalizedBusinessId = normalizedRequiredId("Business ID", businessId);
        CurrentActor actor = currentActorProvider.currentActor();
        BusinessMembershipAuthorization membership =
                authServiceClient.requireBusinessListingPermission(actor.accessToken(), normalizedBusinessId);
        return withImages(listingDraftRepository.findByBusinessId(membership.businessId()));
    }

    @Transactional(readOnly = true)
    // Public detail exposes only approved active listing data and safe image metadata.
    public PublicListingResponse getPublicListing(String listingId) {
        PublicListingResponse listing = listingDraftRepository.findPublicListingById(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new);
        return publicListingWithImagesAndNotice(listing);
    }

    @Transactional(readOnly = true)
    // Public browse is intentionally fixed-size until SEARCH-03 adds cursor pagination.
    public List<PublicListingResponse> getPublicListings() {
        return listingDraftRepository.findPublicListings(PUBLIC_BROWSE_LIMIT).stream()
                .map(this::publicListingWithImagesAndNotice)
                .toList();
    }

    private PublicListingResponse publicListingWithImagesAndNotice(PublicListingResponse listing) {
        return new PublicListingResponse(
                listing.id(),
                listing.sellerType(),
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
                listingMediaRepository.findPublicImagesByListingId(listing.id()));
    }

    @Transactional
    public ListingDraftResponse updateDraft(
            String listingId,
            long expectedVersion,
            CreateListingDraftRequest request) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        if (!canSellerEdit(listing.status())) {
            throw new IllegalArgumentException("Listing cannot be edited in its current state.");
        }
        if (!categoryRepository.activeCategoryExists(request.categoryId())) {
            throw new CategoryNotFoundException();
        }
        if (request.sellerType() != listing.sellerType()) {
            throw new IllegalArgumentException("Listing seller type cannot be changed.");
        }

        ListingDraftUpdate update = switch (listing.sellerType()) {
            case INDIVIDUAL -> individualUpdate(listing, request);
            case BUSINESS -> businessUpdate(listing, request);
        };

        int updated = listingDraftRepository.updateDraft(listing.id(), expectedVersion, update, Instant.now());
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }

        log.info("Updated listing draft id={} sellerType={}", listing.id(), listing.sellerType());
        return withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new));
    }

    @Transactional
    // Seller close removes a listing from any public/moderation queue without deleting its audit history.
    public ListingDraftResponse closeListing(String listingId, long expectedVersion) {
        ListingOwnerSnapshot listing = ownedListing(normalizedRequiredId("Listing ID", listingId));
        if (!canSellerClose(listing.status())) {
            throw new IllegalArgumentException("Only draft, pending-review, or active listings can be closed.");
        }

        int updated = listingDraftRepository.closeListing(listing.id(), expectedVersion, Instant.now());
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }

        log.info("Closed listing id={} sellerType={}", listing.id(), listing.sellerType());
        return withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new));
    }

    @Transactional
    public ListingDraftResponse submitForReview(String listingId, long expectedVersion) {
        OwnedListingSubmission submission = ownedListingSubmission(normalizedRequiredId("Listing ID", listingId));
        ListingOwnerSnapshot listing = submission.listing();
        if (!"DRAFT".equals(listing.status())) {
            throw new IllegalArgumentException("Save listing changes before submitting for review.");
        }
        if (!listingMediaRepository.hasAttachedUploadedImage(listing.id())) {
            throw new IllegalArgumentException("At least one attached image is required before review submission.");
        }

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

        log.info("Submitted listing for review id={} sellerType={} moderationCaseId={} moderationCaseCreated={} submittedByUserId={}",
                listing.id(), listing.sellerType(), moderationCase.id(), moderationCase.created(),
                submission.submittedByUserId());
        return withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new));
    }

    @Transactional(readOnly = true)
    // Admin review queue is scoped to submitted listings that still need a platform decision.
    public List<ListingDraftResponse> getPendingReviewListings() {
        requirePlatformAdmin();
        return withImages(listingDraftRepository.findPendingReview());
    }

    @Transactional(readOnly = true)
    // Lets platform admins inspect active listings before live marketplace edits/removal.
    public ListingDraftResponse getAdminListing(String listingId) {
        requirePlatformAdmin();
        return withImages(listingDraftRepository.findOptionalById(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new));
    }

    @Transactional
    // Applies a limited admin edit to an active approved listing and records reviewer history.
    public ListingDraftResponse updateActiveListingByAdmin(
            String listingId,
            long expectedVersion,
            AdminActiveListingUpdateRequest request) {
        AuthServiceClient.PlatformAdminAuthorization admin = requirePlatformAdmin();
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
        recordAdminListingAction(listing.id(), "ADMIN_EDIT", request.reason(), admin.userId(), expectedVersion + 1, now);
        log.info("Admin edited active listing listingId={} adminUserId={}", listing.id(), admin.userId());
        return withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new));
    }

    @Transactional
    // Removes an active approved listing from public marketplace visibility without deleting history.
    public ListingDraftResponse removeActiveListingByAdmin(
            String listingId,
            long expectedVersion,
            AdminListingRemoveRequest request) {
        AuthServiceClient.PlatformAdminAuthorization admin = requirePlatformAdmin();
        ListingDraftResponse listing = activeApprovedListing(normalizedRequiredId("Listing ID", listingId));
        Instant now = Instant.now();
        int updated = listingDraftRepository.removeActiveListingByAdmin(listing.id(), expectedVersion, now);
        if (updated == 0) {
            throw new ListingVersionConflictException();
        }
        recordAdminListingAction(listing.id(), "ADMIN_REMOVE", request.reason(), admin.userId(), expectedVersion + 1, now);
        log.info("Admin removed active listing listingId={} adminUserId={}", listing.id(), admin.userId());
        return withImages(listingDraftRepository.findOptionalById(listing.id()).orElseThrow(ListingNotFoundException::new));
    }

    @Transactional(readOnly = true)
    // Provides ADM-00 dashboard counts without exposing the full moderation queue.
    public AdminListingModerationSummaryResponse adminModerationSummary() {
        AuthServiceClient.PlatformAdminAuthorization admin = requirePlatformAdmin();
        return new AdminListingModerationSummaryResponse(
                listingDraftRepository.countPendingReview(),
                moderationCaseRepository.countAssignedListingReviewCases(admin.userId()));
    }

    @Transactional(readOnly = true)
    // Reads the case-backed listing moderation queue used by platform admins.
    public List<AdminListingModerationCaseResponse> getListingModerationCases(String filter, String query) {
        AdminActor admin = requirePlatformAdminActor();
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
        AdminActor admin = requirePlatformAdminActor();
        AdminListingModerationCaseResponse moderationCase = loadListingReviewCase(caseId);
        return buildListingModerationCaseDetail(enrichListingReviewCase(moderationCase, admin));
    }

    @Transactional
    // Claims an open listing review case for the current admin using the case version as the lock.
    public AdminListingModerationCaseResponse claimListingModerationCase(String caseId, long expectedVersion) {
        AdminActor admin = requirePlatformAdminActor();
        String normalizedCaseId = normalizedRequiredId("Case ID", caseId);
        int updated = moderationCaseRepository.claimListingReviewCase(
                normalizedCaseId,
                expectedVersion,
                admin.authorization().userId(),
                Instant.now());
        if (updated == 0) {
            throw new ModerationCaseVersionConflictException();
        }
        log.info("Admin claimed listing moderation case caseId={} adminUserId={}",
                normalizedCaseId, admin.authorization().userId());
        return enrichListingReviewCase(loadListingReviewCase(normalizedCaseId), admin);
    }

    @Transactional
    // Releases a claimed listing review case only when it is still assigned to the current admin.
    public AdminListingModerationCaseResponse releaseListingModerationCase(String caseId, long expectedVersion) {
        AdminActor admin = requirePlatformAdminActor();
        String normalizedCaseId = normalizedRequiredId("Case ID", caseId);
        int updated = moderationCaseRepository.releaseListingReviewCase(
                normalizedCaseId,
                expectedVersion,
                admin.authorization().userId(),
                Instant.now());
        if (updated == 0) {
            throw new ModerationCaseVersionConflictException();
        }
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
        AdminActor admin = requirePlatformAdminActor();
        String normalizedCaseId = normalizedRequiredId("Case ID", caseId);
        AdminListingModerationCaseResponse moderationCase = loadListingReviewCase(normalizedCaseId);
        requireResolvableListingReviewCase(moderationCase, admin.authorization().userId(), expectedCaseVersion);

        ListingDraftResponse listing = listingDraftRepository.findOptionalById(moderationCase.listingId())
                .orElseThrow(ListingNotFoundException::new);
        Instant now = Instant.now();
        applyListingModerationDecision(listing, request, admin.authorization().userId(), now);
        int resolved = moderationCaseRepository.resolveListingReviewCase(
                normalizedCaseId,
                expectedCaseVersion,
                admin.authorization().userId(),
                now);
        if (resolved == 0) {
            throw new ModerationCaseVersionConflictException();
        }
        log.info("Admin resolved listing moderation case caseId={} listingId={} adminUserId={}",
                normalizedCaseId, listing.id(), admin.authorization().userId());
        return buildListingModerationCaseDetail(enrichListingReviewCase(loadListingReviewCase(normalizedCaseId), admin));
    }

    @Transactional
    // Applies the platform moderation decision and records immutable reviewer history.
    public ListingModerationDecisionResponse decideListing(
            String listingId,
            long expectedVersion,
            ListingModerationDecisionRequest request) {
        AuthServiceClient.PlatformAdminAuthorization reviewer = requirePlatformAdmin();
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
        return applyListingModerationDecision(listing, listing.version(), request, reviewerUserId, now);
    }

    private ListingModerationDecisionResponse applyListingModerationDecision(
            ListingDraftResponse listing,
            long expectedVersion,
            ListingModerationDecisionRequest request,
            String reviewerUserId,
            Instant now) {
        if (!"PENDING_REVIEW".equals(listing.status()) || !"PENDING".equals(listing.moderationStatus())) {
            log.warn("Rejected listing moderation decision listingId={} status={} moderationStatus={} reason=invalid_state",
                    listing.id(), listing.status(), listing.moderationStatus());
            throw new IllegalArgumentException("Only pending-review listings can receive a moderation decision.");
        }

        String decision = normalizedDecision(request.decision());
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
        int updated = listingDraftRepository.applyModerationDecision(
                listing.id(),
                expectedVersion,
                nextStatus,
                nextModerationStatus,
                publishedAt,
                now);
        if (updated == 0) {
            log.warn("Rejected listing moderation decision listingId={} expectedVersion={} reason=version_conflict",
                    listing.id(), expectedVersion);
            throw new ListingVersionConflictException();
        }
        listingMediaRepository.markImagesModerationStatus(listing.id(), nextModerationStatus, Timestamp.from(now));

        ListingModerationDecisionResponse response = listingModerationDecisionRepository.insert(
                new ListingModerationDecisionInsert(
                        ulidGenerator.next(),
                        listing.id(),
                        decision,
                        reason,
                        reviewerUserId,
                        expectedVersion + 1,
                        now));
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
            String decision,
            String reason,
            String adminUserId,
            long listingVersion,
            Instant now) {
        listingModerationDecisionRepository.insert(new ListingModerationDecisionInsert(
                ulidGenerator.next(),
                listingId,
                decision,
                normalizedRequiredText("Reason", reason, 1000),
                adminUserId,
                listingVersion,
                now));
    }

    @Transactional
    public ListingMediaResponse requestMediaUpload(
            String listingId,
            ListingMediaUploadRequest request) {
        ListingOwnerSnapshot listing = draftListingForMedia(listingId);
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
    // Stores seller-uploaded bytes through the service to avoid browser-to-storage CORS and credential exposure.
    public void uploadMediaContent(String listingId, String mediaId, String contentType, byte[] bytes) {
        draftListingForMedia(listingId);
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
    public ListingMediaResponse confirmMediaUpload(
            String listingId,
            String mediaId,
            ListingMediaConfirmRequest request) {
        draftListingForMedia(listingId);
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

    private ListingMediaContent mediaContent(ListingMediaResponse media) {
        try {
            return new ListingMediaContent(media.contentType(), listingMediaStorage.readObject(media.objectKey()));
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
        listingDraftRepository.markSellerEdited(listing.id(), now);
        List<ListingImageResponse> images = listingMediaRepository.findImagesByListingId(listing.id());
        log.info("Updated listing images listingId={} count={}", listing.id(), images.size());
        return images;
    }

    private ListingDraftResponse createIndividualDraft(CreateListingDraftRequest request) {
        if (request.businessId() != null && !request.businessId().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a business.");
        }
        if (request.sku() != null && !request.sku().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a SKU.");
        }

        CurrentActor actor = currentActorProvider.currentActor();
        IndividualSellerAuthorization seller = authServiceClient.requireActiveIndividualSeller(actor.accessToken());
        ListingLocation location = individualLocation(request, seller);

        return listingDraftRepository.insertDraft(new ListingDraftInsert(
                ulidGenerator.next(),
                ListingSellerType.INDIVIDUAL,
                seller.userId(),
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
                Instant.now()));
    }

    // Draft reads include attached image metadata so seller UIs can show saved media.
    private List<ListingDraftResponse> withImages(List<ListingDraftResponse> listings) {
        return listings.stream()
                .map(this::withImages)
                .toList();
    }

    private ListingDraftResponse withImages(ListingDraftResponse listing) {
        return new ListingDraftResponse(
                listing.id(),
                listing.sellerType(),
                listing.individualSellerUserId(),
                listing.businessId(),
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
                listing.version(),
                listing.createdAt(),
                listing.updatedAt(),
                listingMediaRepository.findImagesByListingId(listing.id()));
    }

    private AdminListingModerationCaseDetailResponse buildListingModerationCaseDetail(
            AdminListingModerationCaseResponse moderationCase) {
        ListingDraftResponse listing = withImages(listingDraftRepository.findOptionalById(moderationCase.listingId())
                .orElseThrow(ListingNotFoundException::new));
        return new AdminListingModerationCaseDetailResponse(
                moderationCase,
                listing,
                listingModerationDecisionRepository.findByListingId(listing.id()));
    }

    private ListingDraftResponse createBusinessDraft(CreateListingDraftRequest request) {
        String businessId = normalizedRequiredId("Business ID", request.businessId());
        if (Boolean.TRUE.equals(request.negotiable())) {
            throw new IllegalArgumentException("Business listings are not negotiable.");
        }
        if (request.quantity() == null) {
            throw new IllegalArgumentException("Business listing quantity is required.");
        }

        CurrentActor actor = currentActorProvider.currentActor();
        BusinessMembershipAuthorization membership =
                authServiceClient.requireBusinessListingPermission(actor.accessToken(), businessId);

        return listingDraftRepository.insertDraft(new ListingDraftInsert(
                ulidGenerator.next(),
                ListingSellerType.BUSINESS,
                null,
                membership.businessId(),
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
                null,
                Instant.now()));
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
        if (!canSellerEdit(listing.status())) {
            throw new IllegalArgumentException("Listing media can be changed only while the listing is draft, pending review, active, or closed.");
        }
        return listing;
    }

    private boolean canSellerEdit(String status) {
        return "DRAFT".equals(status)
                || "PENDING_REVIEW".equals(status)
                || "ACTIVE".equals(status)
                || "CLOSED".equals(status);
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

    private AdminActor requirePlatformAdminActor() {
        CurrentActor actor = currentActorProvider.currentActor();
        return new AdminActor(actor.accessToken(), authServiceClient.requirePlatformAdmin(actor.accessToken()));
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
