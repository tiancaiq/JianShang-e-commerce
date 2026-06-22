package com.msb.ecom.product_service.listing;

import com.msb.ecom.product_service.listing.AuthServiceClient.BusinessMembershipAuthorization;
import com.msb.ecom.product_service.listing.AuthServiceClient.IndividualSellerAuthorization;
import com.msb.ecom.product_service.listing.dto.CategoryResponse;
import com.msb.ecom.product_service.listing.dto.CreateListingDraftRequest;
import com.msb.ecom.product_service.listing.dto.ListingDraftResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListingService {

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
