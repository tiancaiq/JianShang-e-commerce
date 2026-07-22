package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.product_service.dto.BusinessStoreItemCommerceContextPageResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemCommerceContextResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchRequest;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.model.ListingDependencyUnavailableException;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import com.msb.ecom.product_service.repository.BusinessStoreItemSearchCriteria;
import com.msb.ecom.product_service.repository.ListingDraftRepository;
import com.msb.ecom.product_service.repository.ListingMediaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BusinessStoreItemCommerceContextService {

    private static final int DEFAULT_LIMIT = 24;

    private final ListingDraftRepository listingDraftRepository;
    private final ListingMediaRepository listingMediaRepository;
    private final AuthServiceClient authServiceClient;
    private final String internalServiceToken;

    public BusinessStoreItemCommerceContextService(
            ListingDraftRepository listingDraftRepository,
            ListingMediaRepository listingMediaRepository,
            AuthServiceClient authServiceClient,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.listingDraftRepository = listingDraftRepository;
        this.listingMediaRepository = listingMediaRepository;
        this.authServiceClient = authServiceClient;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    // Returns a bounded current catalog page for an authenticated commerce service.
    public BusinessStoreItemCommerceContextPageResponse page(
            String suppliedToken,
            String businessId,
            BusinessStoreItemSearchRequest request) {
        requireInternalToken(suppliedToken);
        String normalizedBusinessId = FixedLengthIds.requireTrimmed("Business ID", businessId, 26);
        BusinessStoreItemSearchCriteria criteria = BusinessStoreItemSearchRequests.criteria(request);
        int limit = BusinessStoreItemSearchRequests.normalizedLimit(request.limit(), DEFAULT_LIMIT);
        List<ListingDraftResponse> fetched =
                listingDraftRepository.searchBusinessStoreItems(normalizedBusinessId, criteria, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<ListingDraftResponse> rows = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore && !rows.isEmpty()
                ? BusinessStoreItemSearchRequests.encodeCursor(rows.get(rows.size() - 1))
                : null;
        Map<String, StoreProvenance> provenances = storeProvenances(rows);
        return new BusinessStoreItemCommerceContextPageResponse(
                rows.stream().map(row -> response(row, provenances.get(row.businessId()))).toList(),
                new BusinessStoreItemCommerceContextPageResponse.PageMetadata(nextCursor, hasMore));
    }

    @Transactional(readOnly = true)
    // Returns current listing ownership and lifecycle facts without exposing seller-only content.
    public BusinessStoreItemCommerceContextResponse item(
            String suppliedToken,
            String businessId,
            String listingId) {
        requireInternalToken(suppliedToken);
        String normalizedBusinessId = FixedLengthIds.requireTrimmed("Business ID", businessId, 26);
        String normalizedListingId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        ListingDraftResponse listing = listingDraftRepository.findOptionalById(normalizedListingId)
                .orElseThrow(ListingNotFoundException::new);
        if (!"BUSINESS".equals(listing.sellerType())
                || !normalizedBusinessId.equals(listing.businessId())) {
            throw new ListingNotFoundException();
        }
        return response(listing);
    }

    @Transactional(readOnly = true)
    // Resolves a business item by its globally unique listing ID for cart orchestration.
    public BusinessStoreItemCommerceContextResponse item(
            String suppliedToken,
            String listingId) {
        requireInternalToken(suppliedToken);
        String normalizedListingId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        ListingDraftResponse listing = listingDraftRepository.findOptionalById(normalizedListingId)
                .orElseThrow(ListingNotFoundException::new);
        if (!"BUSINESS".equals(listing.sellerType())) {
            throw new ListingNotFoundException();
        }
        return response(listing);
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ListingAuthorizationException("Internal commerce service authentication is required.");
        }
    }

    private BusinessStoreItemCommerceContextResponse response(ListingDraftResponse listing) {
        return response(listing, storeProvenance(listing));
    }

    private BusinessStoreItemCommerceContextResponse response(
            ListingDraftResponse listing,
            StoreProvenance provenance) {
        String thumbnailUrl = listingMediaRepository.findPublicImagesByListingId(listing.id()).stream()
                .findFirst()
                .map(image -> image.url())
                .orElse(null);
        return BusinessStoreItemCommerceContextResponse.from(
                listing,
                thumbnailUrl,
                provenance == null ? null : provenance.storeName(),
                provenance == null ? null : provenance.storeSlug(),
                provenance != null && provenance.businessVerified(),
                provenance == null ? null : provenance.publicCity(),
                provenance == null ? null : provenance.publicRegion());
    }

    private StoreProvenance storeProvenance(ListingDraftResponse listing) {
        if (!"BUSINESS".equals(listing.sellerType()) || listing.businessId() == null) {
            return null;
        }
        return storeProvenances(List.of(listing)).get(listing.businessId());
    }

    // Projects only Auth-owned public store labels into the internal cart catalog view.
    private Map<String, StoreProvenance> storeProvenances(List<ListingDraftResponse> listings) {
        Set<String> businessIds = listings.stream()
                .filter(listing -> "BUSINESS".equals(listing.sellerType()))
                .map(ListingDraftResponse::businessId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (businessIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> expectedStoreIds = listings.stream()
                .filter(listing -> "BUSINESS".equals(listing.sellerType()))
                .filter(listing -> listing.businessId() != null && listing.storeId() != null)
                .collect(Collectors.toMap(
                        ListingDraftResponse::businessId,
                        ListingDraftResponse::storeId,
                        (first, ignored) -> first));
        try {
            AuthServiceClient.AdminIdentityLabels labels =
                    authServiceClient.lookupPublicSellerLabels(Set.of(), businessIds);
            if (labels.businesses() == null) {
                return Map.of();
            }
            return labels.businesses().stream()
                    .filter(label -> label.id() != null && expectedStoreIds.get(label.id()) != null)
                    .filter(label -> expectedStoreIds.get(label.id()).equals(label.storeId()))
                    .collect(Collectors.toMap(
                            AuthServiceClient.BusinessIdentityLabel::id,
                            this::storeProvenance,
                            (first, ignored) -> first));
        } catch (AuthServiceClient.DependencyUnavailableException exception) {
            throw new ListingDependencyUnavailableException("Public business store labels are unavailable.", exception);
        }
    }

    private StoreProvenance storeProvenance(AuthServiceClient.BusinessIdentityLabel label) {
        return new StoreProvenance(
                label.storeName(),
                label.storeSlug(),
                label.verified(),
                label.publicCity(),
                label.publicRegion());
    }

    private record StoreProvenance(
            String storeName,
            String storeSlug,
            boolean businessVerified,
            String publicCity,
            String publicRegion) {
    }
}
