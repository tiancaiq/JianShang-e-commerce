package com.msb.ecom.inventory_service.service;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.inventory_service.dto.InternalInventoryAvailabilityResponse;
import com.msb.ecom.inventory_service.repository.InventoryItemRecord;
import com.msb.ecom.inventory_service.repository.InventoryRepository;
import org.springframework.stereotype.Service;

@Service
public class InternalInventoryAvailabilityService {

    private final InventoryRepository repository;
    private final InternalCommerceAuthenticator authenticator;

    public InternalInventoryAvailabilityService(
            InventoryRepository repository,
            InternalCommerceAuthenticator authenticator) {
        this.repository = repository;
        this.authenticator = authenticator;
    }

    // Exposes only the authoritative balance needed by internal commerce callers.
    public InternalInventoryAvailabilityResponse availability(String suppliedToken, String listingId) {
        authenticator.requireAuthenticated(suppliedToken);
        String normalizedListingId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        return repository.findItemByListingId(normalizedListingId)
                .map(this::response)
                .orElseGet(() -> InternalInventoryAvailabilityResponse.uninitialized(normalizedListingId));
    }

    private InternalInventoryAvailabilityResponse response(InventoryItemRecord item) {
        return new InternalInventoryAvailabilityResponse(
                item.listingId(),
                item.businessId(),
                true,
                item.onHand(),
                item.reserved(),
                item.onHand() - item.reserved(),
                item.version());
    }
}
