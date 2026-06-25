package com.msb.ecom.product_service.listing;

import com.msb.ecom.product_service.listing.AuthServiceClient.BusinessMembershipAuthorization;
import com.msb.ecom.product_service.listing.AuthServiceClient.IndividualSellerAuthorization;
import com.msb.ecom.product_service.listing.ListingRepository.ListingOwnerSnapshot;
import com.msb.ecom.product_service.listing.dto.CategoryResponse;
import com.msb.ecom.product_service.listing.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.listing.dto.ListingDraftResponse;
import com.msb.ecom.product_service.listing.dto.ListingMediaConfirmRequest;
import com.msb.ecom.product_service.listing.dto.ListingMediaResponse;
import com.msb.ecom.product_service.listing.dto.ListingMediaUploadRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListingService {

    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024L * 1024L;
    private static final String MEDIA_BUCKET = "listing-media-local";
    private static final Pattern SAFE_FILE_PART = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp");

    private final ListingRepository listingRepository;
    private final AuthServiceClient authServiceClient;
    private final UlidGenerator ulidGenerator;

    @Transactional(readOnly = true)
    public List<CategoryResponse> getActiveCategories() {
        return listingRepository.findActiveCategories();
    }

    @Transactional
    public ListingDraftResponse createDraft(Jwt jwt, CreateListingDraftRequest request) {
        if (!listingRepository.activeCategoryExists(request.categoryId())) {
            throw new CategoryNotFoundException();
        }

        ListingDraftResponse response = switch (request.sellerType()) {
            case INDIVIDUAL -> createIndividualDraft(jwt, request);
            case BUSINESS -> createBusinessDraft(jwt, request);
        };

        log.info("Created listing draft id={} sellerType={}", response.id(), response.sellerType());
        return response;
    }

    @Transactional
    public ListingMediaResponse requestMediaUpload(
            Jwt jwt,
            String listingId,
            ListingMediaUploadRequest request) {
        ListingOwnerSnapshot listing = draftListingForMedia(jwt, listingId);
        String mediaId = ulidGenerator.next();
        String contentType = normalizedContentType(request.contentType());
        long sizeBytes = validSize(request.sizeBytes());
        String checksum = normalizedChecksum(request.checksumSha256());
        String fileName = normalizedOptionalText("File name", request.fileName(), 255);
        String objectKey = objectKey(listing.id(), mediaId, fileName, contentType);

        ListingMediaResponse response = listingRepository.insertMedia(new ListingMediaInsert(
                mediaId,
                listing.id(),
                listing.sellerType(),
                listing.individualSellerUserId(),
                listing.businessId(),
                MEDIA_BUCKET,
                objectKey,
                fileName,
                contentType,
                sizeBytes,
                checksum,
                Instant.now()));

        log.info("Created listing media upload slot listingId={} mediaId={}", listing.id(), response.id());
        return response;
    }

    @Transactional
    public ListingMediaResponse confirmMediaUpload(
            Jwt jwt,
            String listingId,
            String mediaId,
            ListingMediaConfirmRequest request) {
        draftListingForMedia(jwt, listingId);
        ListingMediaResponse media = listingRepository.findMediaById(listingId, mediaId)
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

        ListingMediaResponse response = listingRepository.confirmMedia(
                listingId,
                mediaId,
                sizeBytes,
                checksum == null ? media.checksumSha256() : checksum.toLowerCase(Locale.ROOT),
                Timestamp.from(Instant.now()));

        log.info("Confirmed listing media upload listingId={} mediaId={}", listingId, mediaId);
        return response;
    }

    private ListingDraftResponse createIndividualDraft(Jwt jwt, CreateListingDraftRequest request) {
        if (request.businessId() != null && !request.businessId().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a business.");
        }
        if (request.quantity() != null && request.quantity() != 1) {
            throw new IllegalArgumentException("Individual listing quantity must be 1.");
        }
        if (request.sku() != null && !request.sku().isBlank()) {
            throw new IllegalArgumentException("Individual listing must not include a SKU.");
        }

        IndividualSellerAuthorization seller = authServiceClient.requireActiveIndividualSeller(jwt.getTokenValue());
        ListingLocation location = individualLocation(request, seller);

        return listingRepository.insertDraft(new ListingDraftInsert(
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
                1,
                location.city(),
                location.region(),
                Instant.now()));
    }

    private ListingDraftResponse createBusinessDraft(Jwt jwt, CreateListingDraftRequest request) {
        String businessId = normalizedRequiredId("Business ID", request.businessId());
        if (Boolean.TRUE.equals(request.negotiable())) {
            throw new IllegalArgumentException("Business listings are not negotiable.");
        }
        if (request.quantity() == null) {
            throw new IllegalArgumentException("Business listing quantity is required.");
        }

        BusinessMembershipAuthorization membership =
                authServiceClient.requireBusinessListingPermission(jwt.getTokenValue(), businessId);

        return listingRepository.insertDraft(new ListingDraftInsert(
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

    private ListingOwnerSnapshot draftListingForMedia(Jwt jwt, String listingId) {
        if (jwt == null) {
            throw new IllegalStateException("Authentication is required.");
        }
        ListingOwnerSnapshot listing = listingRepository.findOwnerSnapshot(normalizedRequiredId("Listing ID", listingId))
                .orElseThrow(ListingNotFoundException::new);
        if (!"DRAFT".equals(listing.status())) {
            throw new IllegalArgumentException("Listing media can be changed only while listing is a draft.");
        }
        requireListingOwner(jwt, listing);
        return listing;
    }

    private void requireListingOwner(Jwt jwt, ListingOwnerSnapshot listing) {
        if (listing.sellerType() == ListingSellerType.INDIVIDUAL) {
            IndividualSellerAuthorization seller = authServiceClient.requireActiveIndividualSeller(jwt.getTokenValue());
            if (!listing.individualSellerUserId().equals(seller.userId())) {
                throw new ListingAuthorizationException("Listing belongs to another seller.");
            }
            return;
        }

        BusinessMembershipAuthorization membership =
                authServiceClient.requireBusinessListingPermission(jwt.getTokenValue(), listing.businessId());
        if (!listing.businessId().equals(membership.businessId())) {
            throw new ListingAuthorizationException("Listing belongs to another business.");
        }
    }

    private String normalizedContentType(String value) {
        String normalized = normalizedRequiredText("Content type", value, 80).toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Only JPEG, PNG, and WebP images are supported.");
        }
        return normalized;
    }

    private long validSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Media size is required.");
        }
        if (sizeBytes > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("Media size must be 10 MB or less.");
        }
        return sizeBytes;
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
        String normalized = normalizedRequiredText(fieldName, value, 26);
        if (normalized.length() != 26) {
            throw new IllegalArgumentException(fieldName + " is invalid.");
        }
        return normalized;
    }

    private String normalizedRequiredText(String fieldName, String value, int maxLength) {
        String normalized = normalizedText(fieldName, value, maxLength);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return normalized;
    }

    private String normalizedText(String fieldName, String value, int maxLength) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long.");
        }
        return normalized;
    }

    private String normalizedOptionalText(String fieldName, String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long.");
        }
        return normalized;
    }

    private record ListingLocation(String city, String region) {
    }
}
