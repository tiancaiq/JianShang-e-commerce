package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.product_service.dto.BusinessStoreItemCommerceContextPageResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemCommerceContextResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchRequest;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
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

@Service
public class BusinessStoreItemCommerceContextService {

    private static final int DEFAULT_LIMIT = 24;

    private final ListingDraftRepository listingDraftRepository;
    private final ListingMediaRepository listingMediaRepository;
    private final String internalServiceToken;

    public BusinessStoreItemCommerceContextService(
            ListingDraftRepository listingDraftRepository,
            ListingMediaRepository listingMediaRepository,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.listingDraftRepository = listingDraftRepository;
        this.listingMediaRepository = listingMediaRepository;
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
        return new BusinessStoreItemCommerceContextPageResponse(
                rows.stream().map(BusinessStoreItemCommerceContextResponse::from).toList(),
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
        String thumbnailUrl = listingMediaRepository.findPublicImagesByListingId(listing.id()).stream()
                .findFirst()
                .map(image -> image.url())
                .orElse(null);
        return BusinessStoreItemCommerceContextResponse.from(listing, thumbnailUrl);
    }
}
